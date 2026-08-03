package com.example.flipunlock.hook.systemui

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.replaceResult
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
 *   Hook MiuiFlashlightControllerImpl.setFlipListening() to directly toggle the
 *   flashlight instead of waiting for the flip sensor.
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

        hookSetFlipListening(param.classLoader)
    }

    /**
     * Hook MiuiFlashlightControllerImpl.setFlipListening(boolean) to directly
     * toggle the flashlight instead of waiting for the flip sensor.
     *
     * Original behavior:
     *   setFlipListening(true) → register flip sensor → wait for flip → toggle
     *
     * New behavior:
     *   setFlipListening(true) → directly toggle flashlight via setFlashlight()
     *   setFlipListening(false) → no-op (was: unregister sensor)
     */
    private fun hookSetFlipListening(classLoader: ClassLoader) {
        runCatching {
            val controllerClass = classLoader.loadClass(
                "com.android.systemui.controlcenter.policy.MiuiFlashlightControllerImpl")
            val setFlipListening = controllerClass.getDeclaredMethod(
                "setFlipListening", Boolean::class.javaPrimitiveType!!)
            val setFlashlight = controllerClass.getDeclaredMethod(
                "setFlashlight", Boolean::class.javaPrimitiveType!!)
            val isEnabled = controllerClass.getDeclaredMethod("isEnabled")

            hook(setFlipListening) { chain ->
                val startListening = chain.args[0] as Boolean
                if (startListening) {
                    // Directly toggle flashlight instead of waiting for flip
                    val current = isEnabled.invoke(chain.thisObject) as Boolean
                    setFlashlight.invoke(chain.thisObject, !current)
                    log("FlashlightHook: setFlipListening(true) → toggled flashlight to ${!current}")
                } else {
                    log("FlashlightHook: setFlipListening(false) → no-op")
                }
                // Don't call original — skip flip sensor registration
                null
            }
            log("FlashlightHook: setFlipListening → direct toggle (bypass flip sensor)")
        }.onFailure { log("FlashlightHook: setFlipListening hook failed", it) }
    }

    private fun currentProcessName(): String? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()
}
