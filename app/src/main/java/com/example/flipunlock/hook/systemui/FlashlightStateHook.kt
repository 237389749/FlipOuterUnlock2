package com.example.flipunlock.hook.systemui

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 手电筒翻转提示新解法(2026-08-14, 探索): SystemUI 进程 hook getCurrentState→非0。
 *
 * 背景: a1639c8 尝试 system_server 服务端 getCurrentState→6 替代 FlashlightHook,
 *   实测失败(手电筒弹窗仍在) —— flip1 可能 system_server 断路/方法混淆未 hook 上,
 *   flip2 即使 hook 上弹窗判定也不走服务端值。
 *
 * 判定链(flip1 b5c1-systemui MiuiFlashlightControllerImpl):
 *   L283: if (MiuiMultiDisplayTypeInfo.isFlipDevice()) { mDeviceStateManager = new ... }
 *         ← SystemUI 进程实际为 true(否则 L611 mDeviceStateManager.getCurrentState() 会 NPE 而非弹窗)
 *   L611: if (mDeviceStateManager.getCurrentState() == 0) → 翻转提示弹窗
 *
 * 新解法: hook SystemUI 进程的 framework `DeviceStateManager.getCurrentState()` → 3(展开),
 *   直接在手电筒进程拦截 ② 折叠判定(公开 framework API 名字稳定, 绕开 system_server
 *   服务端 hook 的断路/混淆不可靠性; 不碰 isFlipDevice, 不影响锁屏等 SystemUI 其他逻辑)。
 *
 * 进程: com.android.systemui(可能 pkg=android 回调 → targetPackages 含 "android" + 进程限定)。
 */
object FlashlightStateHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "android")

    override fun setupHooks(param: PackageReadyParam) {
        val proc = currentProcessName()
        if (proc != "com.android.systemui") {
            log("FlashlightStateHook: skip, process=$proc")
            return
        }
        log("FlashlightStateHook: loading for ${param.packageName} (process=$proc)")
        safeHook("FlashlightStateHook") {
            val loader = processClassLoader(param.classLoader)
            runCatching {
                val cls = loader.loadClass("android.hardware.devicestate.DeviceStateManager")
                hook(cls.method("getCurrentState"), replaceResult(3))
                log("FlashlightStateHook: ✓ SystemUI DeviceStateManager.getCurrentState → 3 (手电筒 ②判定失效)")
            }.onFailure { log("FlashlightStateHook: getCurrentState hook failed: ${it.message}") }
        }
    }
}
