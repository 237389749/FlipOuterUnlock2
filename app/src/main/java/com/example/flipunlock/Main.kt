package com.example.flipunlock

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.aod.AodHook
import com.example.flipunlock.hook.identity.CameraReverseHook
import com.example.flipunlock.hook.identity.CutoutAlwaysHook
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
import com.example.flipunlock.hook.system_server.RotationFixHook
import com.example.flipunlock.hook.system_server.SubScreenGesture
import com.example.flipunlock.hook.systemui.ControlCenterHook
import com.example.flipunlock.hook.systemui.FlashlightHook
import com.example.flipunlock.hook.systemui.StatusBarHook
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
        // identity/ — app 端 cutout 四件套（Parser/Display.getCutout/getBoundingRect/mode→3）
        CutoutAlwaysHook,                 // wildcard（camera 排除，保留真实 cutout）
        CameraReverseHook,                // camera 属性反向覆盖：multi_display_type→4（恢复 flip 布局）
        // systemui/ — SystemUI process hooks
        FlashlightHook,                   // bypass "flip to turn on flashlight" on outer screen（控制中心手电筒）
        // [DISABLED 2026-08-10 resetprop 方案] 其他 app hooks 全部注释：
        // DeviceIdentityHook,   // resetprop 已全局替代（属性表=1，静态常量也变）
        // ScreenTypeHook,
        // WidgetRemove,
        // RecentsCacheFix,
        // ControlCenterHook,
        // StatusBarHook,
        // SogouInputHook,
        // LauncherHook,
        // AodHook,
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        Config.logConfig()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting — resetprop 方案精简集")
        RotationFixHook.hook(param)   // 方向修复：DisplayRotation.setUserRotation LOCKED→FREE
        // [DISABLED 2026-08-10 resetprop 方案] 其他 system_server hooks 全部注释：
        // AppRestriction.hook(param)   // resetprop 已解除外屏启动限制
        // AppWhitelist.hook(param)
        // CutoutRemove.hook(param)     // 普通 app cutout 由 CutoutAlwaysHook（app 端）处理
        // AppFullscreen.hook(param)
        // AppContinuity.hook(param)    // resetprop 后内屏流转正常
        // SubScreenGesture.hook(param)
        // InputMethodHook.hook(param)
        // AodHook.hookFramework(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log("Main: onPackageReady pkg=${param.packageName} first=${param.isFirstPackage}")
        // [DISABLED 2026-08-10 resetprop 方案] camera/AppFullscreen 的 app 侧由
        // CameraReverseHook + CutoutAlwaysHook（camera 排除）处理：
        // if (param.packageName == "com.android.camera") { CutoutRemove.hookApp(param) }
        // AppFullscreen.hookApp(param)
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
