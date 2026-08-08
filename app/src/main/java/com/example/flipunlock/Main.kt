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
import com.example.flipunlock.hook.system_server.DisplayTopologyHook
import com.example.flipunlock.hook.system_server.LauncherRouteHook
import com.example.flipunlock.hook.system_server.SubScreenGesture
import com.example.flipunlock.hook.systemui.ControlCenterHook
import com.example.flipunlock.hook.systemui.FlashlightHook
import com.example.flipunlock.hook.systemui.StatusBarHook
import com.example.flipunlock.hook.system_server.InputMethodHook
import com.example.flipunlock.hook.ime.SogouInputHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.DeviceGuard
import com.example.flipunlock.hook.util.log

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

class Main : XposedModule() {

    // ── App-process hooks (onPackageReady) ──────────────────────────
    //     排除法模式：逐个引入，观察变化。
    //     [OFF] = 已注释，待逐步恢复。
    // ──────────────────────────────────────────────────────────────────
    private val packageHooks = listOf<BaseHook>(
        // identity/ — device type spoofing (ROOT HOOK, must be first)
        DeviceIdentityHook,               // isFlipDevice/isFoldDevice/isTinyScreen → false
        // ScreenTypeHook,              // [OFF] Configuration.getScreenType → 0
        // fliphome/ — widget overlay
        // WidgetRemove,                // [OFF] widget overlay complete removal
        // RecentsCacheFix,             // [OFF] 最近任务修复
        // WidgetTouchPassthrough,      // [OFF] FLAG_NOT_TOUCHABLE (already disabled)
        // aod/ — always-on display
        // AodHook,                     // [OFF] AOD outer screen
        // systemui/ — SystemUI process hooks
        // FlashlightHook,              // [OFF] flashlight flip bypass
        // ControlCenterHook,           // [OFF] control center style
        // StatusBarHook,               // [OFF] notification icon limit
        // ime/ — Sogou IME process
        // SogouInputHook,              // [OFF] IME toolbar + clipboard fix
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        Config.logConfig()
        DeviceGuard.logInfo()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting — Flip1 core set (identity + launcher route + launch restriction + display topology)")
        LauncherRouteHook.hook(param)      // updateHomeIntent displayID==5 bypass
        AppRestriction.hook(param)         // 外屏启动限制单门闸 → false
        AppWhitelist.hook(param)           // allowstart 白名单全量注册（内存态）
        DisplayTopologyHook.hook(param)    // 内屏已拆：钉死 state=0，外屏恒为主屏
        // CutoutRemove.hook(param)         // [OFF]
        // AppFullscreen.hook(param)        // [OFF]
        // AppContinuity.hook(param)        // [OFF]
        // AodHook.hookFramework(param)     // [OFF]
        // SubScreenGesture.hook(param)     // [OFF]
        // InputMethodHook.hook(param)      // [OFF]
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log("Main: onPackageReady pkg=${param.packageName} first=${param.isFirstPackage}")
        // Camera process: [OFF]
        // if (param.packageName == "com.android.camera") {
        //     CutoutRemove.hookApp(param)
        // }
        // App-side size-compat: [OFF]
        // AppFullscreen.hookApp(param)
        packageHooks.forEach { hook ->
            val isWildcard = hook.targetPackages.contains("*")
            val isTargeted = hook.targetPackages.contains(param.packageName)
            if (!isWildcard && !isTargeted) return@forEach
            if (isWildcard && !param.isFirstPackage) return@forEach
            log("Main: loading ${hook.javaClass.simpleName} for ${param.packageName}")
            hook.hook(param)
        }
    }
}
