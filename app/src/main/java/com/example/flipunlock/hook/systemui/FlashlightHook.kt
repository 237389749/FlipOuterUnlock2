package com.example.flipunlock.hook.systemui

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.callMethod
import com.example.flipunlock.hook.util.getField
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.method
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Remove the "flip phone to turn on flashlight" restriction on the outer screen.
 *
 * Problem:
 *   On the outer (tiny) screen, clicking the flashlight tile shows a "flip phone"
 *   prompt and waits for the flip sensor instead of toggling directly.
 *
 * Root cause:
 *   MiuiFlashlightTile.handleClick() checks MiuiConfigs.isTinyScreen().
 *   If true + flashlight off → calls setFlipListening(true) → waits for flip sensor.
 *
 * Fix:
 *   Hook MiuiFlashlightTile.handleClick() to bypass the entire flip branch
 *   (which shows the prompt AND starts flip sensor listening).
 *   Directly toggle via setFlashlight() instead.
 */
object FlashlightHook : BaseHook() {

    // "android" is required because LSPosed v2.0.1 only fires onPackageReady("android")
    // in the systemui process — onPackageReady("com.android.systemui") is never called.
    override val targetPackages = listOf("android", "com.android.systemui")

    override fun setupHooks(param: PackageReadyParam) {
        // Process guard: only install in SystemUI
        if (param.packageName == "android") {
            val proc = currentProcessName()
            if (proc != "com.android.systemui") {
                log("FlashlightHook: skip, process=$proc")
                return
            }
            log("FlashlightHook: pkg=android but process=$proc — installing hooks")
        } else {
            log("FlashlightHook: setupHooks pkg=${param.packageName}")
        }

        hookHandleClick(param.classLoader)
    }

    /**
     * Hook MiuiFlashlightTile.handleClick() to bypass the entire flip branch.
     *
     * Original handleClick() flow (tiny screen + flashlight off):
     *   1. mHandler.post(ExternalSyntheticLambda0(controller, 2)) → shows flip prompt
     *   2. setFlipListening(true) → registers flip sensor → waits for flip
     *
     * New flow:
     *   Directly call setFlashlight(!current) + refreshState — no prompt, no sensor.
     *
     * The forceOff/batteryOff check is preserved (shows toast, same as original).
     */
    private fun hookHandleClick(classLoader: ClassLoader) {
        runCatching {
            val tileClass = classLoader.loadClass(
                "com.android.systemui.p037qs.tiles.MiuiFlashlightTile")
            val expandableClass = classLoader.loadClass(
                "com.android.systemui.animation.Expandable")
            // handleClick is declared in parent QSTileImpl, use method() to walk hierarchy
            val handleClick = tileClass.method("handleClick", expandableClass)

            val controllerClass = classLoader.loadClass(
                "com.android.systemui.controlcenter.policy.MiuiFlashlightControllerImpl")
            val setFlashlight = controllerClass.getDeclaredMethod(
                "setFlashlight", Boolean::class.javaPrimitiveType!!)
            val isEnabled = controllerClass.getDeclaredMethod("isEnabled")

            hook(handleClick) { chain ->
                val tile = chain.thisObject
                val controller = tile.getField("flashlightController")!!

                // Preserve forceOff/batteryOff handling (shows toast)
                val forceOff = controller.getField("mForceOff") as Boolean
                val batteryOff = controller.getField("mBatteryOff") as Boolean
                if (forceOff || batteryOff) {
                    // Let original handle this edge case (shows "force off" toast)
                    chain.proceed()
                    return@hook null
                }

                // Directly toggle flashlight — bypass entire flip branch
                val current = isEnabled.invoke(controller) as Boolean
                setFlashlight.invoke(controller, !current)
                // Refresh tile state (the original handleClick also calls refreshState)
                tile.callMethod("refreshState", current)
                log("FlashlightHook: handleClick → toggled flashlight to ${!current} (bypass flip)")
                null
            }
            log("FlashlightHook: handleClick → direct toggle (bypass flip prompt + sensor)")
        }.onFailure { log("FlashlightHook: handleClick hook failed", it) }
    }

    private fun currentProcessName(): String? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()
}
