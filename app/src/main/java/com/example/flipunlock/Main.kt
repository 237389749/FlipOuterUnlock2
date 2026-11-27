package com.example.flipunlock

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.blur.BlurProbe
import com.example.flipunlock.hook.aod.AodHook
import com.example.flipunlock.hook.identity.TinyScreenFixHook
import com.example.flipunlock.hook.ime.ImeNavBarFixHook
import com.example.flipunlock.hook.miuihome.SFDeviceGestureHook
import com.example.flipunlock.hook.miwallpaper.WallpaperIdentityFix
import com.example.flipunlock.hook.settings.ImeBottomSupportHook
import com.example.flipunlock.hook.system_server.AppFullscreen
import com.example.flipunlock.hook.system_server.AppInterceptUnlock
import com.example.flipunlock.hook.system_server.CutoutRemove
import com.example.flipunlock.hook.system_server.ImmersiveStatusBar
import com.example.flipunlock.hook.system_server.Flip1OuterCutoutHook
import com.example.flipunlock.hook.system_server.PackageInstallerSwap
import com.example.flipunlock.hook.system_server.AodFullAodPinHook
import com.example.flipunlock.hook.system_server.DisplayStateHook
import com.example.flipunlock.hook.system_server.ThirdPartyDisplayRotationHook
import com.example.flipunlock.hook.system_server.Flip2CutoutLetterboxHook
import com.example.flipunlock.hook.system_server.RotationFixHook
import com.example.flipunlock.hook.system_server.VolumeKeyRemapFixHook
import com.example.flipunlock.hook.system_server.WallpaperFixHook
import com.example.flipunlock.hook.system_server.LockWallpaperFixHook
import com.example.flipunlock.hook.camera.CameraFixHook
import com.example.flipunlock.hook.camera.SelfieMirrorHook
import com.example.flipunlock.hook.camera.SelfieMirrorEntryHook
import com.example.flipunlock.hook.camera.SelfieUnfoldMirrorHook
import com.example.flipunlock.hook.camera.PreviewOrientationProbe
import com.example.flipunlock.hook.camera.VideoEncoderMirrorHook
import com.example.flipunlock.hook.systemui.FlashlightHook
import com.example.flipunlock.hook.systemui.AodBrightnessHook
import com.example.flipunlock.hook.systemui.AodTraceHook
import com.example.flipunlock.hook.systemui.AodProxFixHook
import com.example.flipunlock.hook.systemui.AodLightMapHook
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
    // 2026-11-23 精简: unlock2 = 属性 1 专用。属性层已原生恢复的功能(hook 版身份/启动拦截/fliphome 桌面)
    //   已整文件删除, 不再用注释当归档 —— 见 refMD/cleaned/Unlock2_Prune_PropertyLayer.md。
    //   历史版本在 git 里; 删掉的文件不要在本文件复活。
    // ──────────────────────────────────────────────────────────────────
    private val packageHooks = listOf<BaseHook>(
        BlurProbe,                      // [2026-11-17] 模糊下发探针(systemui + miuihome): 定位谁在每帧下发 blur; stab 开关可去重
        SFDeviceGestureHook,            // 外屏上滑手势: isInSFDeviceFoldedMode→false + force_fsg_nav_bar→true(Lite) [2026-08-15 恢复]
        SystemUiKeyguardFix,            // systemui 崩溃环兜底: providesTinyKeyguardViewPager 强制 inflate(Lite, flip1 only)
        QSTileMinCountFixHook,          // 控制中心编辑磁贴下限解除(12→4) [2026-08-22 v8: 保险6 插件路径 QSRecord.setRemovable→proceed(true) + 保险7/8 Compose 判定与写库拦截]
        QSPanelWidthFixHook,            // 横屏控制中心磁贴布局撑满屏幕宽度 [2026-08-22: hook MainPanelController.updatePanelWidth, HORIZONTAL→(屏宽-中缝)/2]
        FlashlightHook,                 // 外屏手电筒: 跳过"翻开手机"提示, 磁贴直接开关 [2026-11-23 从观察一期转正; 取代旧的 getCurrentState→6 全局 hack]
        AodHook,                        // AOD 外屏显示(flip1 only; 内部 gate: flip2 SKIP) [2026-08-15 恢复]
        AodTraceHook,                   // [2026-11-25] AOD 全链路探针(doze 状态+dream 生命周期+循环检测), 只读不改
        AodBrightnessHook,              // [2026-11-25] AOD 亮度日志 + 可选亮度下限(persist.flipunlock.aod.brightness)
        AodProxFixHook,                 // [2026-11-25] 单外屏: 遮蔽 nonui prox(33171027), 避免 AOD 被 Prox NEAR 暂停后熄屏
        AodLightMapHook,                // [2026-11-25] 单外屏: 环境光档位 5(明亮室内)当 4 走高亮, 修"AOD 白天看不见"(refMD §11)
        TinyScreenFixHook,              // 属性层死角: getScreenType→0 + isTinyScreen/isFlipTinyScreen→false(修 TIM 通知弹提示)
        ImeBottomSupportHook,           // 设置「全面屏键盘优化」入口: 国际版 IS_INTERNATIONAL_BUILD 硬隐藏解除(scope 需含 com.android.settings)
        ImeNavBarFixHook,               // 输入法底部 48dp 导航栏(⌄/🌐)→高度 0 (scope 需含输入法包)
        CameraFixHook,                  // 相机进程内 multi_display_type→4: 修 flip 外屏相机倒置+黑边(属性1副作用) [2026-08-15 恢复, flip1/2 通用]
        // [2026-11-26 OFF] 用户：相机设置里本来就有「自拍镜像」，不需要模块再补一个 UI。
        //   保留文件便于回退；恢复 = 取消注释 + setprop persist.flipunlock.camera.mirror_entry 1。
        // SelfieMirrorEntryHook,
        SelfieMirrorHook,               // [2026-11-26] 应急: 强制 hook data.data.v.U()→false (开关 camera.no_mirror 默认关; 首选上面的入口方案)
        SelfieUnfoldMirrorHook,         // [2026-11-27] 外屏录像镜像真因: session 参数 selfieUnfold 恒 1 → 改成 0 (开关默认开)
        PreviewOrientationProbe,        // [2026-11-27] 调试: m6.e.C(int) 强制预览方向(persist.flipunlock.camera.preview_orientation, 默认 -1 不干预)
        VideoEncoderMirrorHook,         // [2026-11-27] 路线A: 原生 selfieUnfold=1(预览镜像+方向对), 编码器翻转让成片不镜像
        WallpaperIdentityFix,           // 壁纸引擎进程内 multi_display_type→4 + which 全集: 修属性1下 miwallpaper 每10s NPE 崩溃环 [2026-11-16 新增, scope 需含 com.miui.miwallpaper]
        // 「观察一期」2026-11-23 已收口（见 refMD Unlock2_Prune_PropertyLayer.md §3）：
        //   FlashlightHook 转正注册（上面）；ControlCenterHook / SogouInputHook /
        //   NotifModalFix / NotifFlipTipFix / CameraCutoutFix / FlashlightStateHook
        //   已 git rm（都未注册，功能分别由 QSTile+QSPanel / 属性 1 原生 / CutoutRemove.hookApp 覆盖）。
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
        // [2026-11-16] 新版本通道: 4.0.3 起 InterceptActivityController 由 MiuiStubRegistry.loadJar
        // 从 miui-appcompat.appcontinuity.jar 动态加载(不在主 classpath), 老 hook 拿不到类。
        // 本 hook 走 loadJar/collectImpl 捕获 child ClassLoader; 双守护 flip2 + single_outer。
        AppInterceptUnlock.hook(param)
        // CutoutRemove: flip2 恢复(清零 cutout 数据→挖孔消除); flip1 属性层通杀不需要(2026-08-14 用户确认)
        if (isFlip2Device()) {
            CutoutRemove.hook(param)         // [2026-08-15 恢复] flip2 去挖孔
        }
        if (isFlip2Device()) {
            Flip2CutoutLetterboxHook.hook(param)   // flip2 letterbox 豁免(与 CutoutRemove 同开, 用户要求)
        }
        // flip1 + 属性层(2026-09-24 新增): 原假设"flip1 由属性层覆盖"在国际版 ruyi_global 下不成立
        // —— 实测外屏 display0 cutout=Rect(398,0-0,0) 真实存在, 第三方应用 mAppBounds 被切到
        // Rect(398,0-1208,1392), letterboxReason=DISPLAY_CUTOUT(命中第 3 条, 非 MIUI size-compat)。
        // 只清外屏(displayId 0), 不动内屏 146px 刘海 → 规避 refMD §28.2 的 AOD NPE 链。
        if (!isFlip2Device()) {
            Flip1OuterCutoutHook.hook(param)
        }
        // 2026-11-24: 保留 ! v3.8 曾与 DisplayKeepOnHook 一起退役, 实测 state 3(OPENED)
        // 会渲染成放大版(两次独立启动帧哈希相同), 恢复后正常; 见 refMD §44.17。
        DisplayStateHook.hook(param)
        // [2026-11-28] 第三方 App 在外屏取景上下颠倒（相机方向恒差 180°）：
        //   外屏 installOrientation=180 但框架按 rotation=180 合成 → App 侧标准公式差 180°。
        //   在显示信息出站口对第三方 App 返回 (rotation + installOrientation) mod 4 的副本；
        //   系统相机/SystemUI/桌面等 DENY 包不动。见 refMD Camera.md §13.12。
        ThirdPartyDisplayRotationHook.hook(param)
        PackageInstallerSwap.hook(param)   // [2026-11-25] 默认安装器换 AOSP: 状态层 + 路由层 (find/installer_swap_spec.md)
        ImmersiveStatusBar.hook(param)       // [2026-11-23] 外屏默认沉浸: 状态栏 114px 让给应用(forced-hide 通道)
        AppFullscreen.hook(param)          // size-compat 禁用(保留,全屏相关)
        RotationFixHook.hook(param)        // 旋转解除(Lite 移植): MiuiOrientationImpl 折叠态开放旋转
        // [2026-11-26 OFF] 音量键方向跟随旋转：功能链本身是对的（物理 24→25 实测生效），
        //   但要"每开机必对"需要改成 pull 式（interceptKeyBeforeQueueing 按 DeviceState+rotation
        //   每键求值），而它的收益不大 —— 用户定：默认关闭、保留代码便于回退。
        //   方案与变量/结构全图见 refMD FoldState_Device_Identity.md §44.6.9。
        //   恢复 = 取消下面这行注释 + setprop persist.flipunlock.volume.keyremap 1。
        // VolumeKeyRemapFixHook.hook(param)
        WallpaperFixHook.hook(param)       // 壁纸尺寸钳制: flip1 右侧黑 + flip2 属性层背景一半黑(2026-08-21 重写恢复注册)
        LockWallpaperFixHook.hook(param)   // 锁屏壁纸不被 ambient 隐藏(修「锁屏没有壁纸→纯黑」) [2026-09-24 新增, refMD §44.12]
        AodHook.hookFramework(param)       // AOD 外屏显示(flip1 only, flip2 内部 SKIP; #3 状态钉 DOZE_AOD) [2026-08-15 恢复]
        AodFullAodPinHook.hook(param)      // [2026-11-25] 单外屏 AOD: 钉 mIsFullAod=true, 解除 PMS 5s WAKE_UP 回落(aod.state=2 时)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val proc = currentProcessName()
        // 每个进程只打一条（原来是 system_server 双份 + 每包一条，进程多时刷 logcat）
        if (param.isFirstPackage) {
            log("Main: onPackageReady pkg=${param.packageName} first=true proc=$proc")
        }
        // Camera process: CutoutRemove.hookApp(camera) 相机 cutout 防御(flip1/flip2 都启用) [2026-08-15 恢复]
        // flip1: 防 NPE(用户实测无守护闪退); flip2: 配合 CameraFixHook(属性→4)flip 布局需非 null cutout
        // camera: NPE defence (camera keeps a non-null cutout in its Optional chain)
        // systemui: the MIUI AOD plugin runs inside SystemUI and calls
        //   DisplayUtils.getCutoutPosition() -> display.getCutout() -> NPE when null
        //   (flip2 single-outer-screen: com.miui.aod.DozeHost.create ->
        //    DozeHost.dealWithFlipChange -> DisplayUtils.getCutoutPosition)
        if (param.packageName == "com.android.camera") {
            log("Main: loading CutoutRemove.hookApp for camera")
            CutoutRemove.hookApp(param)
        }
        // Single outer screen (inner panel removed) on flip2: the MIUI AOD plugin runs
        // inside SystemUI and calls DisplayUtils.getCutoutPosition() -> Display.getCutout()
        // -> NPE when CutoutRemove zeroed the spec and the framework computed null.
        // Guarded twice: flip2 only + persist.flipunlock.single_outer (default off,
        // auto-detected by the companion KSU module from the missing synaptics input dev).
        if (param.packageName == "com.android.systemui" &&
            isFlip2Device() && Config.singleOuter
        ) {
            log("Main: loading CutoutRemove.hookApp for systemui (single-outer mode)")
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
