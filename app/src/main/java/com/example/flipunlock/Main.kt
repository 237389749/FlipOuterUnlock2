package com.example.flipunlock

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.aod.AodHook
import com.example.flipunlock.hook.fliphome.RecentsCacheFix
import com.example.flipunlock.hook.fliphome.WidgetRemove
import com.example.flipunlock.hook.fliphome.WidgetTouchPassthrough
import com.example.flipunlock.hook.system_server.AppContinuity
import com.example.flipunlock.hook.system_server.AppFullscreen
import com.example.flipunlock.hook.system_server.AppRestriction
import com.example.flipunlock.hook.system_server.AppWhitelist
import com.example.flipunlock.hook.system_server.CutoutRemove
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.log

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

class Main : XposedModule() {

    // ── App-process hooks (onPackageReady) ──────────────────────────
    //     Each entry is a self-contained feature; dispatch matches the
    //     hook's targetPackages against the ready package ("*" = wildcard,
    //     fires on firstPackage only).
    // ──────────────────────────────────────────────────────────────────
    private val packageHooks = listOf<BaseHook>(
        // fliphome/ — widget overlay
        WidgetRemove,                   // widget overlay complete removal (refreshWindow ADD→REMOVE) — confirmed working
        // RecentsCacheFix,              // TODO: hook installed but effect not verified — needs further debugging
        // WidgetTouchPassthrough,      // FLAG_NOT_TOUCHABLE verified applied via dumpsys, yet touches are STILL
        //                              // intercepted → a second MIUI input mechanism reserves the region while the
        //                              // overlay window exists; flag-only passthrough is insufficient. Kept for reference.
        // aod/ — always-on display on the outer screen (app side: DreamService + runtime DozeMachine)
        AodHook,                        // #5 cutout hook now scoped to AOD call path only
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        Config.logConfig()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting")
        AppRestriction.hook(param)
        AppWhitelist.hook(param)
        CutoutRemove.hook(param)
        AppFullscreen.hook(param)
        AppContinuity.hook(param)
        AodHook.hookFramework(param)    // #5 cutout hook now scoped to AOD call path only
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log("Main: onPackageReady pkg=${param.packageName} first=${param.isFirstPackage}")
        packageHooks.forEach { hook ->
            val isWildcard = hook.targetPackages.contains("*")
            val isTargeted = hook.targetPackages.contains(param.packageName)
            if (!isWildcard && !isTargeted) return@forEach
            // Exclusion check: skip excluded packages (e.g. camera from cutout hooks)
            if (hook.excludedPackages.contains(param.packageName)) {
                log("Main: skipping ${hook.javaClass.simpleName} for ${param.packageName} (excluded)")
                return@forEach
            }
            // "*" hooks use the first package's classloader (framework classes);
            // skip them for subsequent packages to avoid duplicate hooking.
            if (isWildcard && !param.isFirstPackage) return@forEach
            log("Main: loading ${hook.javaClass.simpleName} for ${param.packageName}")
            hook.hook(param)
        }
    }
}
