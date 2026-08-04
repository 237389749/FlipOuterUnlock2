package com.example.flipunlock.hook.systemui

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.method
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Remove the "flip phone to turn on flashlight" dialog on the outer screen.
 *
 * Problem:
 *   On the outer (tiny) screen, clicking the flashlight tile when it's OFF
 *   shows an AlertDialog with a flip-animation video and waits for the
 *   flip sensor before toggling. The user must physically flip the device.
 *
 * Root cause (MiuiFlashlightTile.handleClick, line 98-104):
 *   if (forceOff || batteryOff)           → error toast
 *   else if (isOn || !isTinyScreen)       → direct toggle (normal path)
 *   else if (mTorchAvailable)             → flip dialog + sensor wait (tiny screen ON path)
 *
 * The flip dialog is ExternalSyntheticLambda0(controller, 2) which creates
 * an AlertDialog with a video showing "flip phone to turn on flashlight".
 *
 * Fix: Hook handleClick() to intercept the tiny-screen-turning-ON case.
 *   Instead of showing the dialog + sensor wait, directly toggle via
 *   controller.setFlashlight(!current). All other cases pass through.
 *
 * Ref: refMD Hook_Chain_Map.md §19
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
     * Hook MiuiFlashlightTile.handleClick() to bypass the flip dialog.
     *
     * When on tiny screen + turning ON + not forceOff/batteryOff:
     *   Original → shows AlertDialog + registers flip sensor
     *   Ours     → directly calls setFlashlight(!current)
     *
     * All other cases (turning OFF, non-tiny screen, force/battery off)
     * pass through to the original handleClick().
     */
    private fun hookHandleClick(classLoader: ClassLoader) {
        runCatching {
            val tileClass = classLoader.loadClass(
                "com.android.systemui.qs.tiles.MiuiFlashlightTile")
            val controllerClass = classLoader.loadClass(
                "com.android.systemui.controlcenter.policy.MiuiFlashlightControllerImpl")
            val setFlashlight = controllerClass.getDeclaredMethod(
                "setFlashlight", Boolean::class.javaPrimitiveType!!)
            val isEnabled = controllerClass.getDeclaredMethod("isEnabled")
            val controllerField = tileClass.getDeclaredField("flashlightController")
            controllerField.isAccessible = true
            val forceOffField = controllerClass.getDeclaredField("mForceOff")
            forceOffField.isAccessible = true
            val batteryOffField = controllerClass.getDeclaredField("mBatteryOff")
            batteryOffField.isAccessible = true

            // handleClick is declared directly on MiuiFlashlightTile (not inherited)
            hook(tileClass.method("handleClick")) { chain ->
                val tile = chain.thisObject!!
                val controller = controllerField.get(tile)

                val forceOff = forceOffField.getBoolean(controller)
                val batteryOff = batteryOffField.getBoolean(controller)

                if (!forceOff && !batteryOff) {
                    val isOn = isEnabled.invoke(controller) as Boolean
                    // Flashlight is OFF on tiny screen → skip dialog, toggle directly
                    if (!isOn) {
                        setFlashlight.invoke(controller, true)
                        log("FlashlightHook: handleClick → direct toggle ON (bypassed flip dialog)")
                        return@hook null
                    }
                }
                // All other cases: turning OFF, force/battery off → original behavior
                chain.proceed()
            }
            log("FlashlightHook: handleClick → direct toggle on tiny screen")
        }.onFailure { log("FlashlightHook: handleClick hook failed", it) }
    }

    private fun currentProcessName(): String? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()
}
