package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * App continuity (应用流转): keep the running inner-screen app alive on the
 * outer screen when the device is folded, instead of MIUI replacing it with
 * the home screen.
 *
 * Logic chain (refMD: FoldState_Device_Identity.md §6/§7, verified in FlipRes
 *             miui-appcompat.appcontinuity.jar):
 *
 *   fold event (reason = ON_DISPLAY_FOLD_CHANGED_REASON)
 *     → LauncherSwitchController.moveHomeTaskToFrontAsUser(isFold=true)
 *       → if (reason==ON_DISPLAY_FOLD_CHANGED && mPolicy.isKeepContinuityInFold())
 *             → skip startHomeActivity → running app CONTINUES on outer screen ✓
 *         else
 *             → mService.mInternal.startHomeActivity() → app replaced by home ✗
 *
 *   isKeepContinuityInFold()  (InterceptActivityController)
 *     = isFlipContinuityEnabled()  &&  mIsTopActivityContinuityKeeped
 *       ↑ GATE 2 (master switch)        ↑ GATE 1 (per-app "keep")
 *
 * Both gates must pass, so two hooks are required:
 *
 *   #1 isFlipContinuityEnabledFromSetting(String, int, String) → true
 *      The per-app choke point: the 3-arg overload is the common tail called
 *      by the ActivityRecord / ComponentName overloads AND directly by
 *      isKeepContinuityInFold(). Its result is stored into
 *      mIsTopActivityContinuityKeeped by handleUnfoldedStateLogic /
 *      onTaskConfigChanged / handleActivityContinuity, so forcing it true
 *      marks every foreground app as "keep".
 *
 *   #2 isFlipContinuityEnabled() → true   (DEFENSE, private master switch)
 *      Reads Settings.System "flip_continuity_enabled" (default 0 = OFF).
 *      isKeepContinuityInFold() AND-s it into the final return, so without
 *      this hook continuity still fails whenever the user setting is off —
 *      even though #1 already forced the per-app result. (The old project /
 *      MixFlipMod hook only #1 and rely on the setting being enabled; we
 *      force the master switch too so it works regardless of the setting.)
 *
 * Launcher / home is naturally excluded: isKeepContinuityInFold() returns
 * false for home/launcher activities BEFORE reaching either hooked method, so
 * folding while on the home screen still behaves normally.
 *
 * Bonus: isKeepContinuityInFold() is also consumed by
 * MiuiFlipPolicy.shouldDeviceBeSleep() — forcing it true keeps the device
 * awake (no full sleep) while an app continues on the folded outer screen.
 *
 * Note: this is distinct from AppWhitelist (which enrolls apps into the
 * continuity "allowstart" list so they may LAUNCH on the outer screen without
 * the intercept dialog). AppContinuity makes an already-running app PERSIST
 * across the fold. The two are complementary.
 *
 * Process: system_server
 * Toggle:  persist.flipunlock.app.continuity (default: true)
 */
object AppContinuity {

    private const val CONTROLLER = "com.android.server.wm.InterceptActivityController"

    fun hook(param: SystemServerStartingParam) {
        if (!Config.appContinuity) {
            log("AppContinuity: DISABLED by persist.flipunlock.app.continuity")
            return
        }
        log("AppContinuity: setting up")
        safeHook("AppContinuity") {
            hookContinuityFromSetting(param.classLoader)
            hookContinuityMasterSwitch(param.classLoader)
        }
    }

    // ── #1 isFlipContinuityEnabledFromSetting(String, int, String) → true ──
    //    Per-app choke point → drives mIsTopActivityContinuityKeeped.
    private fun hookContinuityFromSetting(classLoader: ClassLoader) {
        runCatching {
            val controller = classLoader.loadClass(CONTROLLER)
            val method = controller.method(
                "isFlipContinuityEnabledFromSetting",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                String::class.java)
            hook(method, replaceResult(true))
            log("AppContinuity: isFlipContinuityEnabledFromSetting → true (per-app keep)")
        }.onFailure { log("AppContinuity: isFlipContinuityEnabledFromSetting hook failed", it) }
    }

    // ── #2 isFlipContinuityEnabled() → true (private master switch) ──
    //    Final gate of isKeepContinuityInFold(); reads the user setting otherwise.
    private fun hookContinuityMasterSwitch(classLoader: ClassLoader) {
        runCatching {
            val controller = classLoader.loadClass(CONTROLLER)
            val method = controller.method("isFlipContinuityEnabled")
            hook(method, replaceResult(true))
            log("AppContinuity: isFlipContinuityEnabled → true (master switch)")
        }.onFailure { log("AppContinuity: isFlipContinuityEnabled hook failed", it) }
    }
}
