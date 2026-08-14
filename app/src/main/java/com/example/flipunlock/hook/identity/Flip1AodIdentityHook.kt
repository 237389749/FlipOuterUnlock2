package com.example.flipunlock.hook.identity

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 实验(2026-08-14 v3): flip1 SystemUI/AOD 进程 isFlipDevice→true(flip 时钟路径)。
 *
 * 演进:
 *   v1(仅 SystemUI false): AOD 进程独立未覆盖, 无效果。
 *   v2(通配 false): AOD 状态能到 DOZE_AOD(4)但一闪而过 —— 日志实锤
 *     `SCREEN_OFF_SHOW_AOD::MISSING_CLOCK_ID`(KeyguardJankBinder: 无可用时钟)。
 *     原因: isFlipDevice=false → SystemUI/AOD 按普通手机逻辑, flip1 外屏时钟
 *     (FlipLinkageClockCategoryInfo 等)不创建 → clock 缺失 → AOD 帧监控超时熄灭。
 *   v3(本版): SystemUI + com.miui.aod 进程 isFlipDevice→**true** → 时钟走 flip
 *     路径创建(契合 AodHook 注释: FlipLinkageStyleController.isUsingFlip()→true
 *     才是 AOD 正常所需)。仅 flip1。
 */
object Flip1AodIdentityHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "com.miui.aod", "android")

    override fun setupHooks(param: PackageReadyParam) {
        if (!isFlip1Device()) return
        val proc = currentProcessName()
        if (proc != "com.android.systemui" && proc != "com.miui.aod") {
            log("Flip1AodIdentityHook: skip, process=$proc")
            return
        }
        log("Flip1AodIdentityHook: loading for ${param.packageName} (proc=$proc)")
        safeHook("Flip1AodIdentityHook") {
            val loader = processClassLoader(param.classLoader)
            runCatching {
                val cls = loader.loadClass("miui.util.MiuiMultiDisplayTypeInfo")
                hook(cls.method("isFlipDevice"), replaceResult(true))
                log("Flip1AodIdentityHook: ✓ $proc isFlipDevice → true (flip 时钟路径)")
            }.onFailure { log("Flip1AodIdentityHook: isFlipDevice hook failed: ${it.message}") }
        }
    }
}
