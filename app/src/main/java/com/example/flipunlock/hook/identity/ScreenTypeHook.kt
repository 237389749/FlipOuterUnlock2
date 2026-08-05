package com.example.flipunlock.hook.identity

import android.content.res.Configuration
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook Configuration.getScreenType() → 0 (SCREEN_TYPE_EXPAND).
 *
 * MIUI injects a custom screenType field into Configuration:
 *   0 = SCREEN_TYPE_EXPAND  (inner / normal screen)
 *   1 = SCREEN_TYPE_FOLD    (outer screen)
 *  -1 = SCREEN_TYPE_EXTERNAL
 *
 * Used by:
 *   IMiuiConfiguration.isPrimaryScreen()   → getScreenType() == 0
 *   IMiuiConfiguration.isSecondaryScreen() → getScreenType() == 1
 *   FwWindowUtil.isInSmallScreen()         → getScreenType() == 1
 *   DeviceUtils / DeviceHelper             → screenType checks
 *
 * Forcing 0 makes all processes believe they run on the primary screen.
 * Complements DeviceIdentityHook (which spoofs isFlipDevice etc.).
 *
 * Wildcard hook: fires on firstPackage only (Configuration is a framework class).
 */
object ScreenTypeHook : BaseHook() {
    override val targetPackages = listOf("*")

    override fun setupHooks(param: PackageReadyParam) {
        log("ScreenTypeHook: loading for ${param.packageName}")
        runCatching {
            val method = Configuration::class.java.method("getScreenType")
            hook(method, replaceResult(0))
            log("ScreenTypeHook: Configuration.getScreenType → 0 (EXPAND)")
        }.onFailure { log("ScreenTypeHook: failed", it) }
    }
}
