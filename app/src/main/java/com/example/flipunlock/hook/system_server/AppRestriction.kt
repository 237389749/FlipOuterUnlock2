package com.example.flipunlock.hook.system_server

import android.content.ComponentName
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Remove outer screen app launch restrictions.
 *
 * Logic chain (refMD: FoldState_Device_Identity.md §7):
 *
 *   App starts on outer screen
 *     → ActivityStarter checks ContinuityPolicyService
 *       → InterceptActivityController.isInterceptListUnCheckFold(ComponentName)
 *         → true:  launch BLOCKED (continuity dialog shown)
 *         → false: launch ALLOWED
 *
 * This is the SINGLE decision gate. Every app launch on the outer screen
 * passes through it. Hooking it to return false removes ALL restrictions.
 *
 * Downstream (property checks, whitelist, cloud lists) become irrelevant
 * once the decision gate always returns false.
 *
 * Process: system_server
 * Source: miui-appcompat.appcontinuity.jar → InterceptActivityController
 */
object AppRestriction {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.enabled) return
        // Flip2 gate semantics unverified (§34) — Flip1-only until audited.
        if (!DeviceGuard.isFlip1) {
            log("AppRestriction: skipped on gen=${DeviceGuard.gen} (Flip1-only for now)")
            return
        }
        log("AppRestriction: setting up")
        safeHook("AppRestriction") {
            val cls = param.classLoader.loadClass(
                "com.android.server.wm.InterceptActivityController"
            )
            val method = cls.method(
                "isInterceptListUnCheckFold", ComponentName::class.java
            )
            hook(method, replaceResult(false))
            log("AppRestriction: ✓ isInterceptListUnCheckFold → false (all apps allowed)")
        }
    }
}
