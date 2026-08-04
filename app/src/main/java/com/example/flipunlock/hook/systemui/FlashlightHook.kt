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
 *   On the outer (tiny) screen, clicking the flashlight tile when OFF:
 *   1. mHandler.post(ExternalSyntheticLambda0(controller, 2)) → shows AlertDialog with flip video
 *   2. setFlipListening(true) → waits for flip sensor → then toggles
 *
 * The flip prompt is an AlertDialog (NOT showStateMessage).
 * ExternalSyntheticLambda0 case 2 creates the dialog with flip_flashlight_dialog_content layout.
 *
 * Fix (two hooks):
 *   Hook #1: setFlipListening → directly toggle (confirmed working, bypasses sensor wait)
 *   Hook #2: ExternalSyntheticLambda0.run() → skip case 2 (the dialog creation)
 *
 * Ref: refMD Hook_Chain_Map.md §19
 */
object FlashlightHook : BaseHook() {

    override val targetPackages = listOf("android", "com.android.systemui")

    override fun setupHooks(param: PackageReadyParam) {
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
        hookFlipDialogRunnable(param.classLoader)
    }

    /**
     * Hook #1: setFlipListening → directly toggle flashlight.
     * When called with true (user clicked tile on tiny screen):
     *   toggle flashlight immediately, skip flip sensor registration.
     * When called with false (dialog dismissed / sensor fired):
     *   no-op (original would deregister sensor).
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
                null // skip original — don't register flip sensor
            }
            log("FlashlightHook: #1 setFlipListening → direct toggle")
        }.onFailure { log("FlashlightHook: #1 setFlipListening failed", it) }
    }

    /**
     * Hook #2: ExternalSyntheticLambda0.run() → skip case 2 (dialog creation).
     *
     * ExternalSyntheticLambda0 is a synthetic Runnable with multiple cases:
     *   case 0: init camera torch callback
     *   case 1: show error toast (low battery / high temp)
     *   case 2: show flip dialog ← WE WANT TO SKIP THIS
     *   default: init flash device
     *
     * The case ID is stored as an int instance field (f$1 in JADX, obfuscated on device).
     * We find it by type (Int) since the class only has one int field.
     *
     * Class name may have JADX obfuscation prefix (p037 etc.), so we try multiple variants.
     */
    private fun hookFlipDialogRunnable(classLoader: ClassLoader) {
        // Try multiple class name variants
        val candidates = listOf(
            "com.android.systemui.p037qs.controlcenter.policy.MiuiFlashlightControllerImpl\$\$ExternalSyntheticLambda0",
            "com.android.systemui.qs.controlcenter.policy.MiuiFlashlightControllerImpl\$\$ExternalSyntheticLambda0",
            "com.android.systemui.controlcenter.policy.MiuiFlashlightControllerImpl\$\$ExternalSyntheticLambda0",
        )

        var lambdaClass: Class<*>? = null
        for (name in candidates) {
            lambdaClass = runCatching { classLoader.loadClass(name) }.getOrNull()
            if (lambdaClass != null) {
                log("FlashlightHook: #2 found lambda class: $name")
                break
            }
        }

        if (lambdaClass == null) {
            // Fallback: derive from controller's actual package
            lambdaClass = runCatching {
                val controllerPkg = classLoader.loadClass(
                    "com.android.systemui.controlcenter.policy.MiuiFlashlightControllerImpl")
                    .`package`?.name ?: return@runCatching null
                classLoader.loadClass(
                    "$controllerPkg.MiuiFlashlightControllerImpl\$\$ExternalSyntheticLambda0")
            }.getOrNull()
        }

        if (lambdaClass == null) {
            log("FlashlightHook: #2 ExternalSyntheticLambda0 class not found, dialog suppress disabled")
            return
        }

        // Find the int field (case ID) — name is obfuscated on device, find by type
        val caseIdField = lambdaClass.declaredFields.firstOrNull {
            it.type == Int::class.javaPrimitiveType
        }
        if (caseIdField == null) {
            log("FlashlightHook: #2 no int field found in lambda class")
            return
        }
        caseIdField.isAccessible = true

        runCatching {
            val runMethod = lambdaClass.getDeclaredMethod("run")
            runMethod.isAccessible = true

            hook(runMethod) { chain ->
                val caseId = caseIdField.getInt(chain.thisObject)

                if (caseId == 2) {
                    // Case 2: flip dialog — skip entirely
                    log("FlashlightHook: #2 ExternalSyntheticLambda0.run() case 2 → skipped (flip dialog)")
                    return@hook null
                }
                // All other cases: run normally
                chain.proceed()
            }
            log("FlashlightHook: #2 ExternalSyntheticLambda0.run() → skip case 2")
        }.onFailure { log("FlashlightHook: #2 lambda hook failed", it) }
    }

    private fun currentProcessName(): String? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()
}
