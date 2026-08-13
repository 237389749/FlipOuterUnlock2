package com.example.flipunlock

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.identity.CameraCutoutFixHook
import com.example.flipunlock.hook.miuihome.SFDeviceGestureHook
import com.example.flipunlock.hook.system_server.AppFullscreen
import com.example.flipunlock.hook.system_server.CutoutRemove
import com.example.flipunlock.hook.system_server.RotationFixHook
import com.example.flipunlock.hook.systemui.FlashlightHook
import com.example.flipunlock.hook.systemui.SystemUiKeyguardFix
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
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
    // 2026-08-13 精简: 只保留 cutout/全屏相关 + Lite 移植 hook, 其余注释
    private val packageHooks = listOf<BaseHook>(
        FlashlightHook,                 // 控制中心手电筒: 跳过翻转对话框/传感器等待(Lite)
        SFDeviceGestureHook,            // 外屏上滑手势: isInSFDeviceFoldedMode→false + force_fsg_nav_bar→true(Lite)
        SystemUiKeyguardFix,            // systemui 崩溃环兜底: providesTinyKeyguardViewPager 强制 inflate(Lite)
        // CameraCutoutFixHook,         // 相机 NPE 防御 —— 由 CutoutRemove.hookApp(camera) 已覆盖, 不重复
        // DeviceIdentityHook,          // [OFF] 属性层模块已覆盖身份
        // ScreenTypeHook,              // [OFF]
        // WidgetRemove, RecentsCacheFix, // [OFF] fliphome
        // AodHook, ControlCenterHook, StatusBarHook, // [OFF] systemui
        // SogouInputHook,              // [OFF] IME
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        log("Main: onModuleLoaded — process=${currentProcessName()} (system_server? ${currentProcessName() == "system_server"})")
        Config.logConfig()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting — process=${currentProcessName()}")
        // [2026-08-13 精简] 其余 system_server hooks 注释:
        // AppRestriction.hook(param)      // [OFF] 外屏启动限制(待重新定位门闸)
        // AppWhitelist.hook(param)        // [OFF]
        CutoutRemove.hook(param)           // cutout 清零(保留,已验证生效)
        AppFullscreen.hook(param)          // size-compat 禁用(保留,全屏相关)
        // AppContinuity.hook(param)       // [OFF]
        // AodHook.hookFramework(param)    // [OFF]
        // SubScreenGesture.hook(param)    // [OFF]
        // InputMethodHook.hook(param)     // [OFF]
        RotationFixHook.hook(param)        // 旋转解除(Lite 移植): MiuiOrientationImpl 折叠态开放旋转
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val proc = currentProcessName()
        if (proc == "system_server") {
            log("Main: onPackageReady IN SYSTEM_SERVER pkg=${param.packageName} first=${param.isFirstPackage}")
        }
        log("Main: onPackageReady pkg=${param.packageName} first=${param.isFirstPackage} proc=$proc")
        // Camera process: install CutoutRemove without Parser.parse zeroing
        // so camera gets real cutout data (bounds) for layout calculations.
        if (param.packageName == "com.android.camera") {
            log("Main: loading CutoutRemove.hookApp for camera (real cutout preserved)")
            CutoutRemove.hookApp(param)   // 相机 NPE 防御(保留)
        }
        // App-side size-compat disable (complements AppFullscreen system_server hooks)
        AppFullscreen.hookApp(param)      // app 端全屏(保留)
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
