package com.example.flipunlock

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.identity.CameraCutoutFixHook
import com.example.flipunlock.hook.miuihome.SFDeviceGestureHook
import com.example.flipunlock.hook.system_server.AppFullscreen
import com.example.flipunlock.hook.system_server.AppRestriction
import com.example.flipunlock.hook.system_server.CutoutRemove
import com.example.flipunlock.hook.system_server.Flip2CutoutLetterboxHook
import com.example.flipunlock.hook.system_server.RotationFixHook
import com.example.flipunlock.hook.system_server.WallpaperFixHook
import com.example.flipunlock.hook.systemui.FlashlightHook
import com.example.flipunlock.hook.systemui.QSTileMinCountFixHook
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
        SystemUiKeyguardFix,            // systemui 崩溃环兜底: providesTinyKeyguardViewPager 强制 inflate(Lite, flip1 only)
        QSTileMinCountFixHook,          // 控制中心编辑磁贴下限→0(重写双保险, flip1/2 通用)
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
        // [2026-08-14] AppRestriction 重新启用: flip2 通知点击"请在内屏打开"的拦截门
        // (InterceptActivityController.isInterceptListUnCheckFold, 独立于身份, 云端 INTERCEPT_LIST)。
        // flip2 system_server 注入正常(§34.7)可生效; flip1 断路(§43.6.1)装不上, 无影响。
        AppRestriction.hook(param)           // 外屏启动限制解除(通知点击拦截门)
        // CutoutRemove.hook(param)          // [2026-08-14 注释] cutout 清零停用(用户决定, 属性层通杀;
        //                                   //  flip2 由 Flip2CutoutLetterboxHook 豁免 letterbox, 保留 cutout 数据)
        Flip2CutoutLetterboxHook.hook(param) // flip2 letterbox 豁免双保险(FLIP2 gate, §34.3)
        AppFullscreen.hook(param)          // size-compat 禁用(保留,全屏相关)
        // AppContinuity.hook(param)       // [OFF]
        // AodHook.hookFramework(param)    // [OFF]
        // SubScreenGesture.hook(param)    // [OFF]
        // InputMethodHook.hook(param)     // [OFF]
        RotationFixHook.hook(param)        // 旋转解除(Lite 移植): MiuiOrientationImpl 折叠态开放旋转
        WallpaperFixHook.hook(param)       // 壁纸尺寸钳制: 修开机壁纸右侧黑(display1 内屏仍枚举竞态)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val proc = currentProcessName()
        if (proc == "system_server") {
            log("Main: onPackageReady IN SYSTEM_SERVER pkg=${param.packageName} first=${param.isFirstPackage}")
        }
        log("Main: onPackageReady pkg=${param.packageName} first=${param.isFirstPackage} proc=$proc")
        // Camera process: CutoutRemove.hookApp(camera) 相机 NPE 防御随 cutout 清零一并停用(2026-08-14)
        // if (param.packageName == "com.android.camera") {
        //     log("Main: loading CutoutRemove.hookApp for camera (real cutout preserved)")
        //     CutoutRemove.hookApp(param)
        // }
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
