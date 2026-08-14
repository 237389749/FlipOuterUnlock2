package com.example.flipunlock.hook.identity

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 实验(2026-08-14 v2): flip1 所有进程 isFlipDevice→false 强制(范围不限)。
 *
 * 背景: com.miui.aod 是**独立进程**(非 SystemUI 进程), AOD 的 isFlipDevice
 * 判定在 AOD 进程内。v1(仅 SystemUI 进程 + targetPackages 不含 com.miui.aod)
 * 根本没覆盖 AOD 进程 → 无效果。v2 改为通配(" * ", firstPackage 时 hook
 * framework 类, zygote 级 → 所有进程共享), flip1 上强制 isFlipDevice→false。
 *
 * 属性层(multi_display_type=1)理论上已让所有进程类加载时读到 false;
 * 本 hook 防御静态缓存/独立路径死角。用户假设: 内屏才是正常态, 伪手机下
 * AOD 应走普通路径正常显示。
 *
 * flip1 only(flip2 AOD 正常, 不需要)。与 AodHook 互斥(实验期注释 AodHook)。
 */
object Flip1AodIdentityHook : BaseHook() {

    override val targetPackages = listOf("*")

    override fun setupHooks(param: PackageReadyParam) {
        if (!isFlip1Device()) return
        log("Flip1AodIdentityHook: loading for ${param.packageName} (proc=${currentProcessName()})")
        safeHook("Flip1AodIdentityHook") {
            // firstPackage 时 param.classLoader 可能是 framework, 用进程 Application 的 classLoader 兜底
            val loader = processClassLoader(param.classLoader)
            runCatching {
                val cls = loader.loadClass("miui.util.MiuiMultiDisplayTypeInfo")
                hook(cls.method("isFlipDevice"), replaceResult(false))
                log("Flip1AodIdentityHook: ✓ isFlipDevice → false (proc=${currentProcessName()})")
            }.onFailure { log("Flip1AodIdentityHook: isFlipDevice hook failed: ${it.message}") }
        }
    }
}
