package com.example.flipunlock.hook.system_server

import android.content.Intent
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Bypass the fliphome launcher redirect so the inner launcher (miuihome)
 * takes over the outer screen.
 *
 * Generation-divergent chain (refMD: FoldState_Device_Identity.md §29/§34):
 *
 * Flip1 (ruyi) — displayID-based:
 *   ActivityTaskManagerServiceImpl.updateHomeIntent(Intent):
 *     if (ApplicationCompatRouterStub.get().getConfigDisplayID() == 5) {
 *         intent.removeCategory("HOME");
 *         intent.addCategory("SECONDARY_HOME");
 *         intent.setComponent("com.miui.fliphome/.FlipLauncher");
 *     }
 *   This check is based on displayID, NOT isFlipDevice. Even with
 *   isFlipDevice→false, the system still force-redirects HOME to fliphome
 *   when on the outer screen (displayID==5).
 *   NOTE: this method is DEAD CODE on Flip2 — no callers (§34.3).
 *
 * Flip2 (bixi) — fold-state-based (appcontinuity jar):
 *   AppContinuityRouterImpl.updateHomeIntent(Intent):
 *     if (launcherSwitchController.isFolded()) updateFlipHomeIntent(intent);
 *     if (!isFolded() && isFlipHomeNeedStart()) updateFlipHomeIntent(intent);
 *   updateFlipHomeIntent: HOME→SECONDARY_HOME + com.miui.fliphome/.FlipLauncher
 *
 * Hook (both gens): updateHomeIntent → return intent unchanged (skip
 * redirect). This lets the normal HOME resolution take effect.
 *
 * Process: system_server
 * Source: Flip1 miui-services.jar → ActivityTaskManagerServiceImpl
 *         Flip2 miui-appcompat.appcontinuity.jar → AppContinuityRouterImpl
 */
object LauncherRouteHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.enabled) return
        when (DeviceGuard.gen) {
            DeviceGuard.DeviceGen.FLIP1 -> hookFlip1(param)
            DeviceGuard.DeviceGen.FLIP2 -> hookFlip2(param)
            else -> log("LauncherRouteHook: unknown generation, skipped")
        }
    }

    // ── Flip1: displayID==5 redirect (miui-services) ─────────────
    private fun hookFlip1(param: SystemServerStartingParam) {
        log("LauncherRouteHook[flip1]: setting up")
        safeHook("LauncherRouteHook.flip1") {
            val cls = param.classLoader.loadClass(
                "com.android.server.wm.ActivityTaskManagerServiceImpl"
            )
            val method = cls.method("updateHomeIntent", Intent::class.java)
            hook(method) { chain ->
                // Return the original intent unchanged — skip the
                // getConfigDisplayID()==5 → fliphome redirect entirely.
                chain.args[0] as Intent
            }
            log("LauncherRouteHook[flip1]: ✓ updateHomeIntent → passthrough (no fliphome redirect)")
        }
    }

    // ── Flip2: isFolded()/isFlipHomeNeedStart() redirect (appcontinuity jar) ──
    private fun hookFlip2(param: SystemServerStartingParam) {
        log("LauncherRouteHook[flip2]: setting up")
        val clsName = "com.android.server.wm.AppContinuityRouterImpl"
        if (!DeviceGuard.exists(param.classLoader, clsName)) {
            log("LauncherRouteHook[flip2]: $clsName not found, skipped")
            return
        }
        safeHook("LauncherRouteHook.flip2") {
            val cls = param.classLoader.loadClass(clsName)
            val method = cls.method("updateHomeIntent", Intent::class.java)
            hook(method) { chain ->
                // Return the original intent unchanged — skip both the
                // isFolded() and isFlipHomeNeedStart() → fliphome redirects.
                chain.args[0] as Intent
            }
            log("LauncherRouteHook[flip2]: ✓ AppContinuityRouterImpl.updateHomeIntent → passthrough")
        }
    }
}
