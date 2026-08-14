package com.example.flipunlock.hook.identity

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * flip1 身份 hook（2026-08-14 恢复 v2 通配）: 所有进程 isFlipDevice→false。
 *
 * 演进:
 *   v1/v2(通配 false): 手电筒/身份相关 —— 曾用于 AOD 实验, a4502cf 注释。
 *   v3(SystemUI/AOD true): AOD 时钟实验, 非根因, 已弃。
 *   恢复 v2(本版): 手电筒弹窗实锤 SystemUI 进程 isFlipDevice 实际为 **true**
 *     (MiuiFlashlightControllerImpl L283 ① true 才注册翻转传感器监听, L611
 *     onSensorChanged 才会触发弹窗; 用户实测弹窗 = 监听已注册 = ① true
 *     = 属性层在 SystemUI 进程有死角/缓存)。通配 hook 强制所有进程(含
 *     SystemUI/com.miui.aod)isFlipDevice→false。
 *
 * 与 AodHook 并行: AodHook 钉 DOZE_AOD 状态(不依赖 isFlipDevice), AOD 走
 *   非 flip 路径由 AodHook #5(Display.getCutout 按调用栈 NONE)防 NPE。
 *
 * flip1 only(flip2 身份层无此问题)。通配 firstPackage hook framework 类,
 * zygote 级 → 所有进程共享。
 */
object Flip1AodIdentityHook : BaseHook() {

    override val targetPackages = listOf("*")

    override fun setupHooks(param: PackageReadyParam) {
        if (!isFlip1Device()) return
        log("Flip1AodIdentityHook: loading for ${param.packageName} (proc=${currentProcessName()})")
        safeHook("Flip1AodIdentityHook") {
            val loader = processClassLoader(param.classLoader)
            runCatching {
                val cls = loader.loadClass("miui.util.MiuiMultiDisplayTypeInfo")
                hook(cls.method("isFlipDevice"), replaceResult(false))
                log("Flip1AodIdentityHook: ✓ isFlipDevice → false (proc=${currentProcessName()})")
            }.onFailure { log("Flip1AodIdentityHook: isFlipDevice hook failed: ${it.message}") }
        }
    }
}
