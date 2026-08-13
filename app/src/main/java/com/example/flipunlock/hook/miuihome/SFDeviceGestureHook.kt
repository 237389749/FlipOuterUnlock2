package com.example.flipunlock.hook.miuihome

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Restore the swipe-up gesture on the outer (folded) screen when the property
 * layer (persist.sys.multi_display_type=1) is active.
 *
 * Root cause (refMD, 2026-08-10 上滑无反应排查):
 *   The property layer only releases SystemUI's nav bar UI (isFlipTinyScreen
 *   becomes false → NavigationBarControllerImpl creates the gesture pill),
 *   but the swipe-up EXECUTOR lives in the launcher:
 *   - fliphome  : GestureInputHelper.onInputEvent drops touch when
 *                 mIsEnableInput=false (folded gate; fliphome not even running
 *                 once miuihome is the default launcher).
 *   - miuihome  : SpecialFDeviceGestureHelper.isInSFDeviceFoldedMode()=true in
 *                 folded state → BaseRecentsImpl.createAndAddNavStubView /
 *                 showNavStubView / updateFsgWindowState / addBackStubWindow all
 *                 short-circuit → no NavStubView, no swipe-up listener.
 *   isSpecialFDevice() is an MD5 device-fingerprint whitelist
 *   (DeviceConfigs.isSpecialFDevice, miuihome) — the property layer cannot
 *   reach it, so miuihome still thinks it is a special F device while folded.
 *
 * Fix: force isInSFDeviceFoldedMode() → false so miuihome treats the folded
 * outer screen like the expanded one: creates NavStubView (gesture pill),
 * registers the swipe-up listener, and also adds the back stub window
 * (side back gesture) — i.e. the outer screen behaves like a normal phone.
 *
 * Second gate (2026-08-10 real-device finding): NavStubView is only created
 * when mIsFsgNavBar=true, i.e. Settings.Global "force_fsg_nav_bar" ("隐藏屏幕
 * 按键") is 1 — it defaults to 0, so even with the folded gate removed the
 * swipe-up executor is never created. Fix: hook
 * MiuiSettingsUtils.getGlobalBoolean → true for "force_fsg_nav_bar" (module-
 * scoped to com.miui.home only; does NOT touch the global setting, so
 * SystemUI / inner screen / other processes are unaffected, and uninstalling
 * the module restores the original behavior).
 *
 * Side effects (accepted): onFold callback (BaseRecentsImpl:442-449) still
 * removes NavStubView on a fold toggle — acceptable while the device stays
 * folded; other force_fsg_nav_bar consumers in miuihome (GestureLineUtils /
 * DeviceConfigs / StatusBarUtils) all see "fullscreen gesture" consistently.
 *
 * Process: com.miui.home
 * Class/method names are NOT obfuscated in b5c1e89 (classes2.dex).
 */
object SFDeviceGestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.home")

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.gestureSf) {
            log("SFDeviceGestureHook: DISABLED by persist.flipunlock.gesture.sf")
            return
        }
        safeHook("SFDeviceGestureHook") {
            val cls = param.classLoader.findClassUp(
                "com.miui.home.recents.SpecialFDeviceGestureHelper")
                ?: run {
                    log("SFDeviceGestureHook: SpecialFDeviceGestureHelper not found")
                    return@safeHook
                }
            hook(cls.method("isInSFDeviceFoldedMode")) { _ ->
                false
            }
            log("SFDeviceGestureHook: ✓ isInSFDeviceFoldedMode → false (swipe-up restored on outer screen)")

            // 执行器开关：NavStubView 创建条件 mIsFsgNavBar=Settings.Global force_fsg_nav_bar（默认 0）
            // → hook MiuiSettingsUtils.getGlobalBoolean("force_fsg_nav_bar")→true（只影响 miuihome 进程）
            val settingsCls = param.classLoader.findClassUp(
                "com.miui.launcher.utils.MiuiSettingsUtils")
                ?: run {
                    log("SFDeviceGestureHook: MiuiSettingsUtils not found")
                    return@safeHook
                }
            val getGlobalBoolean = settingsCls.method(
                "getGlobalBoolean",
                android.content.ContentResolver::class.java,
                String::class.java)
            hook(getGlobalBoolean) { chain ->
                if (chain.args[1] == "force_fsg_nav_bar") true else chain.proceed()
            }
            log("SFDeviceGestureHook: ✓ force_fsg_nav_bar → true (NavStubView executor enabled)")
        }
    }
}
