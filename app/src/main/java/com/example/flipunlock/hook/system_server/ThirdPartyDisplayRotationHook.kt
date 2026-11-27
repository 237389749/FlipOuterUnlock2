package com.example.flipunlock.hook.system_server

import android.content.Context
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.safeHook
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * 第三方 App 在外屏「上下颠倒」（相机取景转 180°）—— 在**显示信息的出站口**把旋转补回来。
 *
 * ## 现象
 * 微信/支付宝等第三方 App 在外屏（合盖）里扫码，取景画面上下颠倒 180°；系统相机同场景正常。
 * 见 refMD `Camera.md §13.12`、`sessions/2026-11-23.md §37.5`。
 *
 * ## 真因（2026-11-28 判据）
 * 外屏面板是 `installOrientation = ROTATION_180`（相对设备自然方向物理装反），框架为了让内容
 * 看着正，按 `rotation = ROTATION_180` 合成 → `Display.getRotation()` 对 App 报 2。
 * 但**内容相对设备自然方向其实是正的**（180 装反 + 180 合成 = 0），而相机传感器方向是相对
 * 设备自然方向定义的 → 标准 Camera2/CameraX 公式 `(sensorOrientation - getRotation())` 恒差 180°。
 *
 * 实测（`dumpsys display`，合盖态）：外屏 `mBaseDisplayInfo.rotation 0` /
 * `mOverrideDisplayInfo.rotation 2` / `installOrientation ROTATION_180`，旋转 setting 全为 0。
 * 系统相机不受影响：`CameraFixHook` 把相机进程伪造成 flip 身份，MIUI 相机自带 180° 补偿
 * （`selfieUnfold` / `K2.h.f`），所以**相机不能被本钩改到**。
 *
 * ## 做法
 * App 的 `Display.getRotation()` 读 `Display.mDisplayInfo.rotation`，而 `mDisplayInfo` 只来自
 * `IDisplayManager.getDisplayInfo(displayId)` 这一次 binder 调用（`DisplayManagerGlobal`）。
 * 于是在 system_server 侧钩该实现：`displayId == 0` 且调用方不是系统/DENY 包时，返回
 * `(rotation + installOrientation) mod 4` 的**副本**。
 * - 合盖竖屏：2 + 2 = 0 → 第三方 App 算出的取景方向正确。
 * - `installOrientation` 为 0（普通机 / 内屏）→ 自动 no-op。
 *
 * **不注入第三方进程**：LSPosed scope 只有系统组件；改显示信息出站口一处即可覆盖所有第三方
 * App（含以后新装的），不必给每个 App 勾 scope，也不往微信里塞 hook。
 *
 * **不污染共享对象**：`DisplayInfo` 是 `LogicalDisplay` 的内部对象，WMS/SF 也在用；
 * 一律 `new DisplayInfo(old)` 出副本再改，绝不原地改（out 参数形式除外，那个对象是调用方新建的）。
 *
 * **hook 路径里不调 PackageManager**：`getDisplayInfo` 在显示服务持锁时被调用，PM 调用会引入
 * 锁序风险 → DENY 包的 uid 只在启动时解析一次（失败则后台重试，未解析成功前一律不补）。
 *
 * ## 开关
 * `persist.flipunlock.display.thirdparty_rotation`（默认 **true**；`0` = 完全原生）。重启生效。
 * 日志判据：`TpRotation: uid=10323 display0 rotation=2 install=2 -> 0`。
 */
object ThirdPartyDisplayRotationHook {

    private const val TAG = "TpRotation"

    /** 绝不能改的包：这些自己已经按外屏 180° 工作。 */
    private val DENY = listOf(
        "com.android.camera",              // 系统相机：方向本来就是对的，改了反而翻
        "com.android.cameraextensions",
        "com.android.systemui",            // 旋转 / 布局判定
        "com.miui.aod",
        "com.miui.home",
        "com.miui.fliphome",
        "com.miui.miwallpaper",
        "com.miui.gallery",
        "com.android.settings",
    )

    /** 兜底类名：ServiceManager 拿不到实现类时用（AOSP 命名，含混淆风险）。 */
    private val CANDIDATE_CLASSES = listOf(
        "com.android.server.display.DisplayManagerService\$BinderService",
        "com.android.server.display.DisplayManagerService",
    )

    /** DENY 包的 uid；`null` = 还没解析成功（此时一律不补，宁可不动）。 */
    @Volatile
    private var denyUids: Set<Int>? = null

    private val logged = ConcurrentHashMap<String, Boolean>()

    fun hook(param: SystemServerStartingParam) {
        if (!Config.thirdPartyRotation) {
            log("$TAG: DISABLED by persist.flipunlock.display.thirdparty_rotation")
            return
        }
        safeHook(TAG) {
            val cl = param.classLoader
            val infoCls = runCatching { cl.loadClass("android.view.DisplayInfo") }.getOrNull()
            if (infoCls == null) {
                log("$TAG: !! 找不到 android.view.DisplayInfo")
                return@safeHook
            }
            val rotationField = runCatching { infoCls.getField("rotation") }.getOrNull()
            val installField = runCatching { infoCls.getField("installOrientation") }.getOrNull()
            val copyCtor = runCatching {
                infoCls.getDeclaredConstructor(infoCls).also { it.isAccessible = true }
            }.getOrNull()
            if (rotationField == null || installField == null || copyCtor == null) {
                log("$TAG: !! 反射失败 rotation=$rotationField install=$installField copy=$copyCtor")
                return@safeHook
            }

            val classes = findServiceClasses(cl)
            log("$TAG: 候选实现类=${classes.map { it.name }}")

            var hooked = 0
            for (cls in classes) {
                val methods = runCatching { cls.declaredMethods }
                    .getOrDefault(arrayOf<java.lang.reflect.Method>())
                for (m in methods) {
                    if (!m.name.contains("getDisplayInfo")) continue
                    val p = m.parameterTypes
                    val retForm = p.size == 1 && p[0] == Int::class.javaPrimitiveType &&
                            m.returnType.name == "android.view.DisplayInfo"
                    val outForm = p.size == 2 && p[0] == Int::class.javaPrimitiveType &&
                            p[1].name == "android.view.DisplayInfo" && m.returnType == Void.TYPE
                    if (!retForm && !outForm) continue
                    runCatching {
                        m.isAccessible = true
                        if (retForm) hookReturnForm(m, copyCtor, rotationField, installField)
                        else hookOutForm(m, rotationField, installField)
                        hooked++
                        log("$TAG: ✓ hooked ${cls.simpleName}.${m.name}" +
                                "(${p.joinToString { it.simpleName }}) : ${m.returnType.simpleName}")
                    }.onFailure { log("$TAG: hook ${cls.name}.${m.name} 失败: ${it.message}") }
                }
            }
            if (hooked == 0) {
                log("$TAG: !! 一个 getDisplayInfo 都没钩上 —— 外屏第三方旋转仍未修")
            }

            // DENY uid 解析：启动早期 PM 可能还没就绪 → 先试一次，失败就后台重试（最多 10 次）。
            if (resolveDenyUids()) return@safeHook
            Thread {
                repeat(10) {
                    Thread.sleep(3000)
                    if (resolveDenyUids()) return@Thread
                }
                log("$TAG: !! DENY uid 一都没解析到 —— 本钩保持不生效")
            }.start()
        }
    }

    /** 解析 DENY 包 uid（只在启动/后台线程里调，绝不在 getDisplayInfo 路径里调）。 */
    private fun resolveDenyUids(): Boolean {
        val pm = runCatching { systemContext()?.packageManager }.getOrNull() ?: return false
        val set = HashSet<Int>()
        DENY.forEach { pkg ->
            runCatching { set += pm.getPackageUid(pkg, 0) }
        }
        if (set.isEmpty()) return false
        denyUids = set
        log("$TAG: DENY uids=$set（这些包不改）")
        return true
    }

    /** 拿到 `IDisplayManager` 的实现类：优先用 ServiceManager 里注册的真实类（不依赖混淆名）。 */
    private fun findServiceClasses(cl: ClassLoader): List<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        runCatching {
            val sm = cl.loadClass("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "display")
            binder?.javaClass?.let { out += it }
        }.onFailure { log("$TAG: ServiceManager(\"display\") 取实现类失败: ${it.message}") }
        CANDIDATE_CLASSES.forEach { n ->
            runCatching { out += cl.loadClass(n) }.onFailure { log("$TAG: 候选 $n 不存在: ${it.message}") }
        }
        return out.toList()
    }

    /** `DisplayInfo getDisplayInfo(int displayId)`：出站口换副本。 */
    private fun hookReturnForm(
        m: java.lang.reflect.Method,
        copyCtor: Constructor<*>,
        rotationField: Field,
        installField: Field,
    ) {
        hook(m) { chain ->
            val info = chain.proceed() ?: return@hook null
            val displayId = chain.args.getOrNull(0) as? Int ?: return@hook info
            val fixed = corrected(displayId, info, rotationField, installField) ?: return@hook info
            val copy = copyCtor.newInstance(info)
            rotationField.setInt(copy, fixed)
            logOnce(displayId, info, fixed, rotationField, installField)
            copy
        }
    }

    /** `void getDisplayInfo(int displayId, DisplayInfo out)`：先让原方法填，再改它填的对象。 */
    private fun hookOutForm(
        m: java.lang.reflect.Method,
        rotationField: Field,
        installField: Field,
    ) {
        hook(m) { chain ->
            val r = chain.proceed()
            val displayId = chain.args.getOrNull(0) as? Int ?: return@hook r
            val info = chain.args.getOrNull(1) ?: return@hook r
            val fixed = corrected(displayId, info, rotationField, installField) ?: return@hook r
            rotationField.setInt(info, fixed)
            logOnce(displayId, info, fixed, rotationField, installField)
            r
        }
    }

    /** 该补成多少：只对 display 0（外屏）+ 非系统/非 DENY uid + installOrientation != 0 生效。 */
    private fun corrected(
        displayId: Int,
        info: Any,
        rotationField: Field,
        installField: Field,
    ): Int? {
        if (displayId != 0) return null
        if (!allowUid(android.os.Binder.getCallingUid())) return null
        val rot = rotationField.getInt(info)
        val install = installField.getInt(info)
        val fixed = (rot + install) and 3
        return if (fixed == rot) null else fixed
    }

    private fun allowUid(uid: Int): Boolean {
        if (uid < 10_000) return false          // system_server / system uid
        val deny = denyUids ?: return false     // 还没解析出 DENY uid → 宁可不动
        return uid !in deny
    }

    private fun logOnce(
        displayId: Int,
        info: Any,
        fixed: Int,
        rotationField: Field,
        installField: Field,
    ) {
        val uid = android.os.Binder.getCallingUid()
        if (logged.putIfAbsent("$uid/$fixed", true) != null) return
        log(
            "$TAG: uid=$uid display$displayId rotation=${rotationField.getInt(info)}" +
                    " install=${installField.getInt(info)} -> $fixed"
        )
    }

    /** system_server 里拿 Context（无 Application，用 getSystemContext）。 */
    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        (at.getMethod("currentApplication").invoke(null) as? Context)
            ?: (at.getMethod("getSystemContext").invoke(null) as? Context)
    }.getOrNull()
}
