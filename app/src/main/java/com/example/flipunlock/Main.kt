package com.example.flipunlock

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.aod.AodHook
import com.example.flipunlock.hook.identity.CameraCutoutFixHook
// import com.example.flipunlock.hook.identity.Flip1AodIdentityHook  // [2026-08-15 注释] 最小集合实验
import com.example.flipunlock.hook.identity.TinyScreenFixHook
import com.example.flipunlock.hook.miuihome.SFDeviceGestureHook
import com.example.flipunlock.hook.system_server.AppFullscreen
// import com.example.flipunlock.hook.system_server.AppRestriction     // [2026-08-15 注释] 最小集合实验
import com.example.flipunlock.hook.system_server.CutoutRemove
import com.example.flipunlock.hook.system_server.DisplayStateHook
import com.example.flipunlock.hook.system_server.Flip2CutoutLetterboxHook
import com.example.flipunlock.hook.system_server.RotationFixHook
import com.example.flipunlock.hook.system_server.VolumeKeyRemapFixHook
import com.example.flipunlock.hook.system_server.WallpaperFixHook
import com.example.flipunlock.hook.camera.CameraFixHook
// import com.example.flipunlock.hook.systemui.FlashlightHook          // [2026-08-15 注释] 最小集合实验
// import com.example.flipunlock.hook.systemui.NotifFlipTipFixHook     // [2026-08-15 注释] 最小集合实验
// import com.example.flipunlock.hook.systemui.NotifModalFixHook       // [2026-08-15 注释] 最小集合实验
import com.example.flipunlock.hook.systemui.FlashlightStateHook
import com.example.flipunlock.hook.systemui.QSTileMinCountFixHook
import com.example.flipunlock.hook.systemui.QSPanelWidthFixHook
import com.example.flipunlock.hook.systemui.SystemUiKeyguardFix
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.log
// import com.example.flipunlock.hook.util.isFlip1Device  // [2026-08-15 注释] 最小集合实验(相机分支已注释)
import com.example.flipunlock.hook.util.isFlip2Device

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
        // FlashlightHook,               // [2026-08-15 注释] 最小集合实验: 保留 TinyScreenFix/SystemUiKeyguardFix
        // FlashlightStateHook,          // [2026-08-14 注释] SystemUI getCurrentState→3 对手电筒无效果(实测), 回 FlashlightHook
        SFDeviceGestureHook,            // 外屏上滑手势: isInSFDeviceFoldedMode→false + force_fsg_nav_bar→true(Lite) [2026-08-15 恢复]
        SystemUiKeyguardFix,            // systemui 崩溃环兜底: providesTinyKeyguardViewPager 强制 inflate(Lite, flip1 only)
        QSTileMinCountFixHook,          // 控制中心编辑磁贴下限解除(12→4) [2026-08-19 恢复+重写 v6: 保险6 插件路径 QSRecord.setRemovable 恒 true, 内屏样式版生效]
        QSPanelWidthFixHook,            // 横屏控制中心磁贴布局撑满屏幕宽度 [2026-08-22: hook MainPanelController.updatePanelWidth, HORIZONTAL→(屏宽-中缝)/2]
        // NotifFlipTipFixHook,          // [2026-08-15 注释] 最小集合实验
        // NotifModalFixHook,            // [2026-08-15 注释] 最小集合实验
        // FlipQsFixHook,               // [2026-08-15 注释] flip 版磁贴设置页(miui.systemui.plugin)非目标, 目标是通用版控制中心
        // AodHook,                      // [2026-08-15 注释] 最小集合实验
        AodHook,                        // AOD 外屏显示(flip1 only; 内部 gate: flip2 SKIP) [2026-08-15 恢复]
        // Flip1AodIdentityHook,         // [2026-08-15 注释] 最小集合实验
        TinyScreenFixHook,              // 属性层死角: getScreenType→0 + isTinyScreen/isFlipTinyScreen→false(修 TIM 通知弹提示)
        CameraFixHook,                  // 相机进程内 multi_display_type→4: 修 flip 外屏相机倒置+黑边(属性1副作用) [2026-08-15 恢复, flip1/2 通用]
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
        // AppRestriction.hook(param)       // [2026-08-15 注释] 最小集合实验
        // CutoutRemove: flip2 恢复(清零 cutout 数据→挖孔消除); flip1 属性层通杀不需要(2026-08-14 用户确认)
        if (isFlip2Device()) {
            CutoutRemove.hook(param)         // [2026-08-15 恢复] flip2 去挖孔
        }
        // Flip2CutoutLetterboxHook.hook(param)  // [2026-08-15 恢复] flip2 letterbox 豁免(用户要求与 CutoutRemove 同开)
        if (isFlip2Device()) {
            Flip2CutoutLetterboxHook.hook(param)   // flip2 letterbox 豁免
        }
        DisplayStateHook.hook(param)         // DeviceState 钉死: 1b 恒布局 + getCurrentState(flip2→6 双屏/ flip1→0 外屏)
        AppFullscreen.hook(param)          // size-compat 禁用(保留,全屏相关)
        // AppContinuity.hook(param)       // [OFF]
        // AodHook.hookFramework(param)    // [OFF]
        // SubScreenGesture.hook(param)    // [OFF]
        // InputMethodHook.hook(param)     // [OFF]
        RotationFixHook.hook(param)        // 旋转解除(Lite 移植): MiuiOrientationImpl 折叠态开放旋转
        VolumeKeyRemapFixHook.hook(param)  // 恢复 flip 折叠态音量键方向跟随旋转(supportVolumeKeyRemap→true)
        WallpaperFixHook.hook(param)       // 壁纸尺寸钳制: flip1 右侧黑 + flip2 属性层背景一半黑(2026-08-21 重写恢复注册)
        AodHook.hookFramework(param)       // AOD 外屏显示(flip1 only, flip2 内部 SKIP; #3 状态钉 DOZE_AOD) [2026-08-15 恢复]
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val proc = currentProcessName()
        if (proc == "system_server") {
            log("Main: onPackageReady IN SYSTEM_SERVER pkg=${param.packageName} first=${param.isFirstPackage}")
        }
        log("Main: onPackageReady pkg=${param.packageName} first=${param.isFirstPackage} proc=$proc")
        // Camera process: CutoutRemove.hookApp(camera) 相机 cutout 防御(flip1/flip2 都启用) [2026-08-15 恢复]
        // flip1: 防 NPE(用户实测无守护闪退); flip2: 配合 CameraFixHook(属性→4)flip 布局需非 null cutout
        if (param.packageName == "com.android.camera") {
            log("Main: loading CutoutRemove.hookApp for camera")
            CutoutRemove.hookApp(param)
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
