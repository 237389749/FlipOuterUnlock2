package com.example.flipunlock

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.aod.AodHook
import com.example.flipunlock.hook.identity.DeviceIdentityHook
import com.example.flipunlock.hook.identity.ScreenTypeHook
import com.example.flipunlock.hook.fliphome.RecentsCacheFix
import com.example.flipunlock.hook.fliphome.WidgetRemove
import com.example.flipunlock.hook.fliphome.WidgetTouchPassthrough
import com.example.flipunlock.hook.system_server.AppContinuity
import com.example.flipunlock.hook.system_server.AppFullscreen
import com.example.flipunlock.hook.system_server.AppRestriction
import com.example.flipunlock.hook.system_server.AppWhitelist
import com.example.flipunlock.hook.system_server.CutoutRemove
import com.example.flipunlock.hook.system_server.SubScreenGesture
import com.example.flipunlock.hook.systemui.ControlCenterHook
import com.example.flipunlock.hook.systemui.FlashlightHook
import com.example.flipunlock.hook.systemui.StatusBarHook
import com.example.flipunlock.hook.systemui.ToastHook
import com.example.flipunlock.hook.system_server.InputMethodHook
import com.example.flipunlock.hook.ime.SogouInputHook
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
        // identity/ — device type spoofing (ROOT HOOK, must be first)
        DeviceIdentityHook,               // isFlipDevice/isFoldDevice/isTinyScreen → false, static field clearing
        ScreenTypeHook,                   // Configuration.getScreenType → 0 (EXPAND)
        // fliphome/ — widget overlay
        WidgetRemove,                   // widget overlay complete removal (refreshWindow ADD→REMOVE) — confirmed working
        RecentsCacheFix,                // 最近任务修复：缓存刷新 + needRemoveTask过滤绕过
        // WidgetTouchPassthrough,      // FLAG_NOT_TOUCHABLE verified applied via dumpsys, yet touches are STILL
        //                              // intercepted → a second MIUI input mechanism reserves the region while the
        //                              // overlay window exists; flag-only passthrough is insufficient. Kept for reference.
        // aod/ — always-on display on the outer screen (app side: DreamService + runtime DozeMachine)
        AodHook,                        // #5 cutout hook now scoped to AOD call path only
        // systemui/ — SystemUI process hooks
        FlashlightHook,                 // bypass "flip to turn on flashlight" on outer screen
        ControlCenterHook,              // restore normal (non-compact) control center style
        StatusBarHook,                  // expand notification icon limit on outer screen
        ToastHook,                      // SystemUIToast.getGravity → 0x51 (toast centering fix)
        // ime/ — Sogou IME process
        SogouInputHook,                 // IME toolbar + clipboard fix (DexKit)
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
        SubScreenGesture.hook(param)      // double-tap-to-sleep on outer screen
        InputMethodHook.hook(param)        // IME freedom: allow any rotation, unlock IME choice
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log("Main: onPackageReady pkg=${param.packageName} first=${param.isFirstPackage}")
        // Camera process: install CutoutRemove without Parser.parse zeroing
        // so camera gets real cutout data (bounds) for layout calculations.
        if (param.packageName == "com.android.camera") {
            log("Main: loading CutoutRemove.hookApp for camera (real cutout preserved)")
            CutoutRemove.hookApp(param)
        }
        // App-side size-compat disable (complements AppFullscreen system_server hooks)
        AppFullscreen.hookApp(param)
        packageHooks.forEach { hook ->
            val isWildcard = hook.targetPackages.contains("*")
            val isTargeted = hook.targetPackages.contains(param.packageName)
            if (!isWildcard && !isTargeted) return@forEach
            // "*" hooks use the first package's classloader (framework classes);
            // skip them for subsequent packages to avoid duplicate hooking.
            if (isWildcard && !param.isFirstPackage) return@forEach
            log("Main: loading ${hook.javaClass.simpleName} for ${param.packageName}")
            hook.hook(param)
        }
    }
}
