package com.example.flipunlock.hook.miwallpaper

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.function.Function

/**
 * miwallpaper(壁纸引擎)进程内恢复 flip 身份 —— 修 OS4.0.3 + 属性 1 下
 * 「开机 16s 起每 ~10s 崩一次 / 桌面与锁屏完全没有壁纸」。
 *
 * ── 现象(2026-11-16 实机, c94cabc7 / OS4.0.3.0.XOHCNXM / 内屏已拆) ─────────────
 *   `com.miui.miwallpaper` 从 uptime 16.6s 起, Application.onCreate 必崩, 每 ~10s
 *   一条 `system_app_crash`(dropbox 18 条), 最终被 AM 放弃重启 → 壁纸引擎整个不工作。
 *   副作用: main logcat 被刷屏(256 KiB ring buffer 只剩 ~6 秒历史), 连带 AOD 的
 *   `keyguardeditor` 也报 `lateinit property miuiWallpaperManager has not been initialized`。
 *
 * ── 根因(反编译 /product/app/MiWallpaper/MiWallpaper.apk 实锤) ────────────────
 *   MiWallpaperApplication.onCreate
 *    └ WallpaperServiceController.<init>            ← 初始化「外屏(小屏)」引擎
 *       └ d0(4)/d0(8)  → EffectUtils.e(EffectUtils.d(which)) = e(4)
 *          └ e() 内: strT = MiuiWallpaperManagerService.t(homeWhich=4)
 *                    strT2 = t(lockWhich=8)
 *             └ t(int) = B.get(which).b()  →  B 里没有 key 4/8 → **返回 null**
 *                └ WallpaperTypeUtils.f(null) 第一行就是 `str.getClass()` → NPE → 进程死
 *
 *   而「B 为什么没有 4/8」= 身份税:
 *     MiuiWallpaperManager.<clinit>
 *       MI_WALLPAPER_WHICH_ALL = (isFoldDevices() || isFlipDevice()) ? 15 : 3
 *       isFlipDevice() → miui.util.MiuiMultiDisplayTypeInfo.isFlipDevice()
 *                      → (SystemProperties.getInt("persist.sys.multi_display_type",1) & 255) == 4
 *       属性 1(伪装普通手机) → false → WHICH_ALL=3 → 只装载 which 1/2(box 里 4/8 永不入表)
 *     但 `WallpaperServiceController` 仍按「物理上有块小屏」去初始化 4/8 的引擎 ⇒ 取数必 null。
 *   四个 which 的 xml 其实都在(/data/system/theme_magic/users/0/wallpaper/data/
 *   home.xml / lock.xml / small_home.xml / small_lock.xml), 只是没被读。
 *
 * ── 修法(三层, 上游 → 读点) ─────────────────────────────────────────────────
 *   ① 进程内身份还原(与 CameraFixHook 同套路): 让本进程读到的
 *      `persist.sys.multi_display_type` = 4 → isFlipDevice()=true
 *      → IS_FLIP_DEVICE / IS_FOLDABLE_DEVICE = true → WHICH_ALL=15 → 4 个 which 全装载。
 *   ② forAllWallpaper(mask) 兜底: 若 ① 因类加载时机失效(静态块已跑过), 把
 *      mask=3 改写成 15 —— 这个方法调用发生在装载时, 与类初始化顺序无关。
 *   ③ 读点兜底: `WallpaperTypeUtils.f(String)` 对 null 直接返回 false(不再 NPE),
 *      保证任何漏网的 null 只退化不崩进程。
 *
 *   进程内虚拟改属性, 不动属性层真实值(同 §34.6 HideOuterHook / CameraFixHook 模式),
 *   纯运行时、无持久化副作用, 关开关即回原生(回原生 = 崩溃环)。
 *
 * 进程: com.miui.miwallpaper (需 LSPosed scope 勾选该包)
 * 开关: persist.flipunlock.wallpaper.identity (默认 true)
 */
object WallpaperIdentityFix : BaseHook() {

    override val targetPackages = listOf("com.miui.miwallpaper")

    private const val PROP_TYPE = "persist.sys.multi_display_type"
    private const val FLIP_TYPE = 4

    private const val CL_MULTI = "miui.util.MiuiMultiDisplayTypeInfo"
    private const val CL_MGR = "com.miui.miwallpaper.MiuiWallpaperManager"
    private const val CL_WTYPE = "com.miui.miwallpaper.utils.WallpaperTypeUtils"

    /** MI_WALLPAPER_WHICH_ALL 在非 flip/fold 下是 3(1|2), flip 下是 15(1|2|4|8) */
    private const val WHICH_ALL_NON_FOLDABLE = 3
    private const val WHICH_ALL_FOLDABLE = 15

    /** 每层只打一次日志 —— 这几个 hook 在热路径上, 逐次 log 会把 logcat ring buffer 冲爆 */
    private val propReported = java.util.concurrent.atomic.AtomicBoolean(false)
    private val typeNullReported = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.wallpaperIdentity) {
            log("WallpaperIdentity: DISABLED by persist.flipunlock.wallpaper.identity")
            return
        }
        // 双守护: 只对「flip2 + 内屏已拆」生效, 别的机型/形态一律不动
        if (!isFlip2Device() || !Config.singleOuter) {
            log("WallpaperIdentity: skip (flip2=${isFlip2Device()} singleOuter=${Config.singleOuter})")
            return
        }
        log("WallpaperIdentity: loading for ${param.packageName} (proc=${currentProcessName()})")
        val cl = processClassLoader(param.classLoader)

        hookPropIdentity(cl)
        hookWhichMask(cl)
        hookTypePredicate(cl)
    }

    /**
     * ① 进程内身份还原: SystemProperties.getInt("persist.sys.multi_display_type") → 4。
     *
     * MiuiMultiDisplayTypeInfo.<clinit> 只读这一个 key 得到 sDeviceType,
     * 之后 isFlipDevice() 恒为 `sDeviceType == 4`。本进程内返回 4 不影响别的进程。
     */
    private fun hookPropIdentity(cl: ClassLoader) = safeHook("WallpaperIdentity.prop") {
        val spClass = cl.loadClass("android.os.SystemProperties")
        // 有 symbol 的进程会读到真实值 → 只在 target 方法被调用时改写
        runCatching {
            val m = spClass.method("getInt", String::class.java, Int::class.javaPrimitiveType!!)
            hook(m) { chain ->
                if (chain.args[0] == PROP_TYPE) {
                    if (isFlip2Device()) {
                        if (propReported.compareAndSet(false, true)) {
                            log("WallpaperIdentity: ✓ multi_display_type → 4 (flip) [getInt(String,int)]")
                        }
                        return@hook FLIP_TYPE
                    }
                }
                chain.proceed()
            }
        }.onFailure { log("WallpaperIdentity: getInt(String,int) hook failed: ${it.message}") }
        // 单参版本兜底(部分调用点用 getInt(String))
        runCatching {
            val m = spClass.method("getInt", String::class.java)
            hook(m) { chain ->
                if (chain.args[0] == PROP_TYPE && isFlip2Device()) return@hook FLIP_TYPE
                chain.proceed()
            }
        }.onFailure { log("WallpaperIdentity: getInt(String) hook failed: ${it.message}") }

        // 诊断: 若 MiuiMultiDisplayTypeInfo 已被别的时机初始化, 这里能看出本进程拿到的设备类型
        runCatching {
            val multi = cl.findClassUp(CL_MULTI) ?: return@runCatching
            val getType = multi.method("getDeviceType")
            val type = runCatching { getType.invoke(null) }.getOrNull()
            log("WallpaperIdentity: getDeviceType()=$type (4=flip, 1=phone)")
        }
    }

    /**
     * ② 数据源兜底: forAllWallpaper(Function, mask) 把 3 改写成 15。
     *
     * mask=3 时 MiuiWallpaperManagerService.<init> 只装载 which 1/2; 改成 15 后
     * 四个 which 的 xml(都真实存在)全装载 → t(4)/t(8) 不再返回 null。
     * ⚠️ args 不可变(libxposed getArgs 只读), 必须用 proceed(arrayOf(...)) 传新参数。
     */
    private fun hookWhichMask(cl: ClassLoader) = safeHook("WallpaperIdentity.mask") {
        val mgr = cl.findClassUp(CL_MGR) ?: run {
            log("WallpaperIdentity: $CL_MGR 找不到, skip mask hook")
            return@safeHook
        }
        val m = mgr.method("forAllWallpaper", Function::class.java, Int::class.javaPrimitiveType!!)
        hook(m) { chain ->
            val mask = chain.args.getOrNull(1) as? Int
            if (mask == WHICH_ALL_NON_FOLDABLE) {
                log("WallpaperIdentity: forAllWallpaper mask 3 → 15 (装载 which 4/8)")
                chain.proceed(arrayOf<Any?>(chain.args[0], WHICH_ALL_FOLDABLE))
            } else {
                chain.proceed()
            }
        }
        log("WallpaperIdentity: ✓ $CL_MGR.forAllWallpaper hooked")
    }

    /**
     * ③ 读点兜底: WallpaperTypeUtils.f(String) 收到 null 时返回 false 而不是 NPE。
     *
     * f() 的语义是「这个 type 是不是 image 类」, null 显然不是 ⇒ 返回 false 语义正确,
     * 且把崩溃降级为「不走 effect 引擎」。
     */
    private fun hookTypePredicate(cl: ClassLoader) = safeHook("WallpaperIdentity.f") {
        val wt = cl.findClassUp(CL_WTYPE) ?: run {
            log("WallpaperIdentity: $CL_WTYPE 找不到, skip f() guard")
            return@safeHook
        }
        val m = wt.method("f", String::class.java)
        hook(m) { chain ->
            if (chain.args.getOrNull(0) == null) {
                if (typeNullReported.compareAndSet(false, true)) {
                    log("WallpaperIdentity: ✓ f(null) → false (空类型防护命中)")
                }
                return@hook false
            }
            chain.proceed()
        }
        log("WallpaperIdentity: ✓ $CL_WTYPE.f(String) 空值防护")
    }
}
