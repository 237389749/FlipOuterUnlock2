package com.example.flipunlock.hook.systemui

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix MIUI SystemUIToast gravity bug causing toast left-shift.
 *
 * MIUI renders toasts through SystemUI (ToastUI → SystemUIToast).
 * SystemUIToast has a BUG: it reads config_whenToStartHubModeDefault
 * (value=0 = NO_GRAVITY) instead of config_toastDefaultGravity
 * (value=0x51 = CENTER_HORIZONTAL | BOTTOM).
 *
 * With gravity=0, toast goes to x=0 regardless of cutout state.
 * This is an independent bug from the cutout — even with full cutout
 * removal (Parser.parse zero), gravity=0 still positions toast at left edge.
 *
 * Fix:
 *   Hook #1: SystemUIToast.getGravity() → 0x51
 *   Hook #2: ClickableToast constructor → mGravity = 0x51 (same bug)
 *
 * Process: systemui
 *
 * refMD: DisplayCutout.md §24, Hook_Chain_Map.md §14
 */
object ToastHook : BaseHook() {
    override val targetPackages = listOf("android", "com.android.systemui")

    override fun setupHooks(param: PackageReadyParam) {
        // LSPosed v2.0.1: onPackageReady only fires for "android" in SystemUI process
        if (param.packageName == "android") {
            val proc = currentProcessName()
            if (proc != "com.android.systemui") {
                log("ToastHook: skip, process=$proc")
                return
            }
            log("ToastHook: pkg=android but process=$proc — installing hooks")
        } else {
            log("ToastHook: setupHooks pkg=${param.packageName}")
        }

        safeHook("ToastHook") {
            hookToastGravity(param.classLoader)
        }
    }

    /**
     * SystemUIToast.getGravity() → 0x51 (CENTER_HORIZONTAL | BOTTOM).
     * Also fixes ClickableToast constructor (same MIUI gravity bug).
     */
    private fun hookToastGravity(classLoader: ClassLoader) {
        // #1 SystemUIToast.getGravity() → 0x51
        runCatching {
            val cls = classLoader.loadClass("com.android.systemui.toast.SystemUIToast")
            val method = cls.getDeclaredMethod("getGravity")
            method.isAccessible = true
            hook(method, replaceResult(0x51))
            log("ToastHook: ✓ SystemUIToast.getGravity → 0x51")
        }.onFailure { log("ToastHook: SystemUIToast.getGravity failed", it) }

        // #2 ClickableToast constructor → mGravity = 0x51 (same bug)
        runCatching {
            val cls = classLoader.loadClass(
                "com.android.systemui.statusbar.views.ClickableToast")
            val ctor = cls.declaredConstructors.firstOrNull { it.parameterCount >= 3 }
            if (ctor != null) {
                ctor.isAccessible = true
                hook(ctor, after { chain, _ ->
                    runCatching { chain.thisObject?.setField("mGravity", 0x51) }
                    null
                })
                log("ToastHook: ✓ ClickableToast gravity → 0x51")
            }
        }.onFailure { log("ToastHook: ClickableToast failed", it) }
    }

    private fun currentProcessName(): String? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()
}
