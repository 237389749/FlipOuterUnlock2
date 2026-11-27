package com.example.flipunlock.hook.camera

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.processClassLoader
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 外屏预览方向探针（2026-11-27，调试用，默认关闭）。
 *
 * ## 背景
 * 折叠/单外屏下"UI 正、预览上下颠倒"。实测日志：
 * ```
 * MCAM_BaseModule: [OrientationTrace] checkDisplayOrientation: 180 | 270
 * MCAM_OrientationEvent: updatePreviewOrientation: 180 -> 180, realOrientation = 217
 * ```
 * 即：`K2.h.f()`（flip 下把非 0 的显示旋转一律变 180）→ `Ce.C.e(180, caps)` →
 * 后摄 `(sensor 90 - 180 + 360) % 360 = 270`，写进 `m6.e.C(int)`（= `getCameraDisplayOrientation()`）。
 * 外屏面板 `installOrientation=2`（物理装反 180°），所以这个 270 可能多转了 180。
 *
 * ## 做法
 * 把 `m6.e.C(int)` 收到的值改成 `persist.flipunlock.camera.preview_orientation` 指定值
 * （-1 = 不干预）。这样**不用每次改代码**，setprop + `am force-stop com.android.camera`
 * 就能 A/B 0/90/180/270，找出与外屏 UI 对齐的那个角度。定位完删掉本文件。
 */
object PreviewOrientationProbe : BaseHook() {

    override val targetPackages = listOf("com.android.camera")

    private const val TAG = "PreviewOrient"

    override fun setupHooks(param: PackageReadyParam) {
        val process = currentProcessName()
        if (process != "com.android.camera") {
            log("$TAG: skip, process=$process")
            return
        }
        val forced = Config.previewOrientationOverride
        if (forced < 0) {
            log("$TAG: off（persist.flipunlock.camera.preview_orientation 未设）")
            return
        }
        val cl = runCatching { processClassLoader(param.classLoader) }.getOrDefault(param.classLoader)
        runCatching {
            val cls = cl.loadClass("m6.e")
            val m = cls.declaredMethods.firstOrNull {
                it.name == "C" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Integer.TYPE
            } ?: run {
                log("$TAG: m6.e.C(int) 不存在")
                return
            }
            m.isAccessible = true
            hook(m) { chain ->
                val orig = chain.args.getOrNull(0)
                log("$TAG: C($orig) → $forced")
                chain.proceed(arrayOf(forced))
            }
            log("$TAG: ✓ m6.e.C(int) hooked → $forced")
        }.onFailure { log("$TAG: hook failed: ${it.message}") }
    }
}
