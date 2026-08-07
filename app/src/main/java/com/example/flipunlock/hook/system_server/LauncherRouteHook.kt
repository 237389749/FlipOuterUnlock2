package com.example.flipunlock.hook.system_server

import android.content.Intent
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Bypass the hardcoded displayID==5 → fliphome launcher redirect.
 *
 * Logic chain (refMD: FoldState_Device_Identity.md §29):
 *
 *   ActivityTaskManagerServiceImpl.updateHomeIntent(Intent):
 *     if (ApplicationCompatRouterStub.get().getConfigDisplayID() == 5) {
 *         intent.removeCategory("HOME");
 *         intent.addCategory("SECONDARY_HOME");
 *         intent.setComponent("com.miui.fliphome/.FlipLauncher");
 *     }
 *
 * This check is based on displayID, NOT isFlipDevice. Even with
 * isFlipDevice→false, the system still force-redirects HOME to fliphome
 * when on the outer screen (displayID==5).
 *
 * Hook: updateHomeIntent → return intent unchanged (skip redirect).
 * This lets the normal HOME resolution take effect.
 *
 * Process: system_server
 * Source: miui-services.jar → ActivityTaskManagerServiceImpl
 */
object LauncherRouteHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.enabled) return
        log("LauncherRouteHook: setting up")
        safeHook("LauncherRouteHook") {
            val cls = param.classLoader.loadClass(
                "com.android.server.wm.ActivityTaskManagerServiceImpl"
            )
            val method = cls.method("updateHomeIntent", Intent::class.java)
            hook(method) { chain ->
                // Return the original intent unchanged — skip the
                // getConfigDisplayID()==5 → fliphome redirect entirely.
                chain.args[0] as Intent
            }
            log("LauncherRouteHook: ✓ updateHomeIntent → passthrough (no fliphome redirect)")
        }
    }
}
