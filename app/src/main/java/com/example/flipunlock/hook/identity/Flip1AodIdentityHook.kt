package com.example.flipunlock.hook.identity

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 实验(2026-08-14): flip1 SystemUI/AOD 进程 isFlipDevice→false 强制。
 *
 * 背景: AOD 代码(com.miui.aod)跑在 SystemUI 进程。属性层(multi_display_type=1)
 * 理论上已让 MiuiMultiDisplayTypeInfo.isFlipDevice()=false(SystemUI 类加载时读属性),
 * 但用户观察到 AOD 物理屏无显示(状态停在 DOZE_SUSPEND), 且认为"内屏才是正常态"——
 * 伪装成普通手机后 AOD 应走普通路径正常显示。本 hook 防御 SystemUI 进程内
 * isFlipDevice 判定的死角(静态缓存/独立路径), 验证补上后 AOD 是否恢复正常。
 *
 * flip1 only(flip2 AOD 正常, 不需要)。与 AodHook 互斥(实验期注释 AodHook)。
 * 注: DeviceIdentityHook 排除 SystemUI(§38 锁屏布局), 本 hook 专门覆盖 SystemUI
 *     进程(只 hook isFlipDevice, 最小面; 属性层本已 false, 无新增 §38 风险)。
 */
object Flip1AodIdentityHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "android")

    override fun setupHooks(param: PackageReadyParam) {
        if (!isFlip1Device()) return
        val proc = currentProcessName()
        if (proc != "com.android.systemui") {
            log("Flip1AodIdentityHook: skip, process=$proc")
            return
        }
        log("Flip1AodIdentityHook: loading for ${param.packageName} (process=$proc)")
        safeHook("Flip1AodIdentityHook") {
            // SystemUI 可能以 pkg=android 回调, param.classLoader 是 framework 不含 app 类
            // → 用进程 Application 的 classLoader(参考 processClassLoader util)
            val loader = processClassLoader(param.classLoader)
            runCatching {
                val cls = loader.loadClass("miui.util.MiuiMultiDisplayTypeInfo")
                hook(cls.method("isFlipDevice"), replaceResult(false))
                log("Flip1AodIdentityHook: ✓ SystemUI isFlipDevice → false (AOD 普通路径实验)")
            }.onFailure { log("Flip1AodIdentityHook: isFlipDevice hook failed: ${it.message}") }
        }
    }
}
