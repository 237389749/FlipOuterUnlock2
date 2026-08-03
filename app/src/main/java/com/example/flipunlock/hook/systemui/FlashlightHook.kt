package com.example.flipunlock.hook.systemui

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.getField
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.method
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Remove the "flip phone to turn on flashlight" restriction on the outer screen.
 *
 * Problem:
 *   On the outer (tiny) screen, clicking the flashlight tile:
 *   1. mHandler.post(ExternalSyntheticLambda0(controller, 2)) → shows flip prompt
 *   2. setFlipListening(true) → waits for flip sensor → then toggles
 *
 * Fix (two hooks):
 *   Hook #1: setFlipListening → directly toggle flashlight (bypass sensor wait)
 *   Hook #2: showStateMessage → no-op on flashlight tile (suppress flip prompt)
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
        hookShowStateMessage(param.classLoader)
    }

    /**
     * Hook #1: setFlipListening → directly toggle flashlight.
     * Confirmed working for toggle, prompt still shows (handled by hook #2).
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
                    val current = isEnabled.invoke(chain.thisObject) as Boolean
                    setFlashlight.invoke(chain.thisObject, !current)
                    log("FlashlightHook: setFlipListening(true) → toggled to ${!current}")
                }
                // Don't call original — skip flip sensor registration
                null
            }
            log("FlashlightHook: #1 setFlipListening → direct toggle")
        }.onFailure { log("FlashlightHook: #1 setFlipListening failed", it) }
    }

    /**
     * Hook #2: QSTileImpl.showStateMessage() → no-op on flashlight tile.
     * The flip prompt (arg=2 lambda) likely calls showStateMessage to display
     * "flip phone to turn on flashlight" text. Suppressing it removes the prompt.
     */
    private fun hookShowStateMessage(classLoader: ClassLoader) {
        runCatching {
            // showStateMessage is in QSTileImpl (parent of MiuiFlashlightTile)
            val tileImplClass = classLoader.loadClass(
                "com.android.systemui.p037qs.tileimpl.QSTileImpl")
            val showStateMessage = tileImplClass.method("showStateMessage", CharSequence::class.java)

            hook(showStateMessage) { chain ->
                val tileClassName = chain.thisObject.javaClass.name
                if (tileClassName.contains("FlashlightTile") || tileClassName.contains("MiuiFlashlightTile")) {
                    log("FlashlightHook: #2 showStateMessage suppressed on $tileClassName")
                    return@hook null  // suppress prompt
                }
                chain.proceed()
            }
            log("FlashlightHook: #2 showStateMessage → suppress on flashlight tile")
        }.onFailure { log("FlashlightHook: #2 showStateMessage failed", it) }
    }

    private fun currentProcessName(): String? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()
}
