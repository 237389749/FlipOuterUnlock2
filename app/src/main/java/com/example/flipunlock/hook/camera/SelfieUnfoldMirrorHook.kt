package com.example.flipunlock.hook.camera

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.processClassLoader
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 外屏录像「镜像」的真正源头：session 参数 `com.xiaomi.sessionparams.selfieUnfold`（2026-11-27）。
 *
 * ## 现象
 * 折叠/单外屏，系统相机录出来的 **mp4 左右反**（文字反、二维码反），而同一场景的 **照片正常**。
 * 取景预览也是反的。
 *
 * ## 实测证据（flip2 / OS4.0.3 / 3.27-unlock2）
 * 录 5 s 视频，logcat（tag `MCAM_VideoModuleDevice`）只有两条相关参数：
 * ```
 * updateFrontMirrorEnabledParameter: parameter = false   ← xiaomi.flip.enabled（session）false
 * updatePreviewMirrorParam: 1                            ← selfieUnfold = 1
 * applyFrontMirror: false                                ← 录像 request 也不翻
 * ```
 * ⇒ 抓帧后 mp4 确实左右反 ⇒ 只能是 `selfieUnfold=1` 让 HAL 把 **整个 session（预览+录像）** 翻了。
 *
 * ## 为什么是 1
 * `z3.d.x(k)`（`BaseModuleDevice`，视频走 `VideoModuleDevice`）：
 * ```
 * int i = ((Se.b.Y() && K2.m.b()) || K2.b.e0()) ? 1 : 0;
 * if (caps.v0(w0.T)) { z1.a(w0.T, i); }     // w0.T = E1.F0(5) = "com.xiaomi.sessionparams.selfieUnfold"
 * ```
 * `K2.b.e0() = X() || c0() || d0()`，其中 `d0() = (o() == SECOND_SCREEN_MODE)` —— 单外屏就是
 * SECOND_SCREEN_MODE ⇒ **恒为 1**，与「自拍镜像」pref 无关。
 * 这也解释了：改 `pref_front_mirror_boolean_key` 只动照片（走 `xiaomi.flip.enabled` / `isFrontMirror()`），
 * 动不了录像。
 *
 * ## 做法
 * 运行时把写进 session 参数的这个值改成 `0`（纯运行时，不写磁盘；卸载即恢复）。
 * 钩两个写点（哪个存在钩哪个）：
 *   - `n9.z1.a(B0, T)`  ← `BaseModuleDevice`/`VideoModuleDevice` 走这个（已实测命中）
 *   - `oa.g.c(B0, T)`   ← 另一条 applier 路径（`Ut.a.d` 用它）
 * 只对 `key.b().getName() == "com.xiaomi.sessionparams.selfieUnfold"` 的条目生效，其余原样放行。
 *
 * ## 开关
 * `persist.flipunlock.camera.no_selfie_unfold_mirror`（默认 **true**）。改完需
 * `am force-stop com.android.camera`。
 */
object SelfieUnfoldMirrorHook : BaseHook() {

    override val targetPackages = listOf("com.android.camera")

    private const val TAG = "UnfoldMirror"
    private const val KEY_NAME = "com.xiaomi.sessionparams.selfieUnfold"

    override fun setupHooks(param: PackageReadyParam) {
        val process = currentProcessName()
        if (process != "com.android.camera") {
            log("$TAG: skip, process=$process")
            return
        }
        if (!Config.cameraNoSelfieUnfoldMirror) {
            log("$TAG: skip, persist.flipunlock.camera.no_selfie_unfold_mirror=0")
            return
        }
        val cl = runCatching { processClassLoader(param.classLoader) }.getOrDefault(param.classLoader)
        val n = hookSetter(cl, "n9.z1", "a") + hookSetter(cl, "oa.g", "c")
        if (n == 0) log("$TAG: !! 没有钩到 session 参数写点（类名可能变了）")
    }

    /** 钩 `(B0 key, T value)` 这种 session 参数写点；只改 selfieUnfold。 */
    private fun hookSetter(cl: ClassLoader, clsName: String, methodName: String): Int {
        return runCatching {
            val cls = cl.loadClass(clsName)
            val m = cls.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterCount == 2
            }
            if (m == null) {
                log("$TAG: $clsName.$methodName(2 args) 不存在，跳过")
                return 0
            }
            m.isAccessible = true
            hook(m) { chain ->
                val args = chain.args
                val value = args.getOrNull(1)
                val keyName = keyName(args.getOrNull(0))
                if (value is Int) {
                    val tag = "$clsName.$methodName:$keyName=$value"
                    if (seen.add(tag)) log("$TAG: seen $tag")
                }
                if (value is Int && value != 0 && isMirrorKey(keyName)) {
                    log("$TAG: $keyName $value → 0（$clsName.$methodName）")
                    return@hook chain.proceed(arrayOf(args[0], 0))
                }
                chain.proceed()
            }
            log("$TAG: ✓ $clsName.$methodName hooked")
            1
        }.getOrElse {
            log("$TAG: $clsName.$methodName hook failed: ${it.message}")
            0
        }
    }

    /** B0 包装：`b()` 取底层 Key，`getName()` 取厂商 tag 名。 */
    private fun keyName(wrapper: Any?): String? = runCatching {
        if (wrapper == null) return@runCatching null
        val key = wrapper.javaClass.getMethod("b").invoke(wrapper) ?: return@runCatching null
        key.javaClass.getMethod("getName").invoke(key) as? String
    }.getOrNull()

    /** 诊断期宽松匹配：名字里带 selfie/mirror/flip 的都算；确认后收紧成精确 key。 */
    private fun isMirrorKey(name: String?): Boolean {
        if (name == null) return false
        if (name == KEY_NAME) return true
        val n = name.lowercase()
        return n.contains("selfie") || n.contains("mirror") || n.contains("flip")
    }

    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())
}
