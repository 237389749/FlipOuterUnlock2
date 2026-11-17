package com.example.flipunlock.hook.util

/**
 * Feature toggles via SystemProperties. All default to true (enabled).
 *
 * List all keys and current values:
 *   getprop | grep persist.flipunlock
 *
 * Set before reboot:
 *   setprop persist.flipunlock.enable false              # master kill switch
 *   setprop persist.flipunlock.display.dual false        # dual display
 *   setprop persist.flipunlock.display.aod false         # outer screen AOD
 *   setprop persist.flipunlock.display.cutout false      # remove cutout
 *   setprop persist.flipunlock.display.fullscreen false  # force app fullscreen (no compat letterbox)
 *   setprop persist.flipunlock.app.continuity false      # app continuity (keep running app on fold)
 *   setprop persist.flipunlock.gesture.home false        # bottom gestures
 *   setprop persist.flipunlock.gesture.back false        # back gestures
 *   setprop persist.flipunlock.ui.lockscreen false       # lock screen layout
 *   setprop persist.flipunlock.ui.widget false           # disable widget overlay
 *   setprop persist.flipunlock.ui.controlcenter false    # control center restore
 *   setprop persist.flipunlock.ui.recentsmenu false      # recents long-press menu
 *   setprop persist.flipunlock.ime false                 # input method freedom
 *   setprop persist.flipunlock.camera true               # camera front→back redirect (broken)
 *   setprop persist.flipunlock.ui.launcherdensity true    # launcher density tweak (not needed)
 *
 * ═══ Dependency / Coupling Notes ═══
 *
 * display.dual ── FOUNDATION ──┐
 *   If disabled, state=6 is not forced. The system may fall back to native
 *   flip behavior (state=0 CLOSED or state=2 OPEN). All hooks that depend
 *   on outer=displayId=0 topology may still work but layout may differ.
 *   ↓ affects: display.aod, gesture.home, ui.lockscreen (display routing)
 *   ✗ no effect: display.cutout, gesture.back, ui.widget, ime
 *
 * gesture.back ←──── gesture.home ────→ gesture.back
 *   Coupled! Both target the same launcher identity problem:
 *   - gesture.back disables FlipLauncher (com.miui.fliphome)
 *   - gesture.home forces getIsUseMiuiHomeAsDefaultHome→true
 *   If gesture.back=OFF (FlipLauncher active) + gesture.home=ON:
 *     FlipLauncher is default home → conflict with miuihome NavStubView
 *   If gesture.back=ON (FlipLauncher disabled) + gesture.home=OFF:
 *     No default home app → HOME intent may show "choose launcher" dialog
 *   Recommended: keep both ON or both OFF together.
 *
 * display.aod ── depends on ── display.dual
 *   AOD targets displayId from DreamService (outer screen in state=6).
 *   display.dual must be ON for correct display routing.
 *
 * ui.lockscreen ── depends on ── display.dual
 *   LockScreenHook hooks SystemUI which runs on displayId=0 (outer in state=6).
 *   Independent of gesture toggles.
 *
 * display.cutout ── INDEPENDENT ──
 *   Cutout/letterbox hooks operate at WindowManager/framework level.
 *   No dependency on any other toggle.
 *
 * display.fullscreen ── INDEPENDENT ──
 *   Disables MIUI flip size-compat letterbox (MIUI_SIZE_COMPAT_MODE) so apps
 *   fill the whole outer screen. Separate mechanism from the cutout — an app
 *   can have the cutout removed yet still be letterboxed to a fixed aspect
 *   ratio. No dependency on any other toggle.
 *
 * app.continuity ── INDEPENDENT ──
 *   Keeps the running inner-screen app alive on the outer screen when folded
 *   (forces InterceptActivityController.isKeepContinuityInFold gates). Works
 *   at the activity/task level; no dependency on any other toggle.
 *
 * ui.widget ── INDEPENDENT ──
 *   WatchOverlayHook operates in fliphome process only.
 *   No dependency on any other toggle.
 *
 * ui.controlcenter ── INDEPENDENT ──
 *   Control center style restoration targets SystemUI plugin context.
 *   No dependency on any other toggle.
 *
 * ui.recentsmenu ── INDEPENDENT ──
 *   Recents long-press menu targets fliphome recents views.
 *   No dependency on any other toggle.
 *
 * ime ── INDEPENDENT ──
 *   Input method hooks in system_server + Sogou process.
 *   No dependency on any other toggle.
 */
object Config {
    private val keys = listOf(
        "persist.flipunlock.enable",
        "persist.flipunlock.display.dual",
        "persist.flipunlock.display.aod",
        "persist.flipunlock.display.cutout",
        "persist.flipunlock.display.fullscreen",
        "persist.flipunlock.app.continuity",
        "persist.flipunlock.gesture.home",
        "persist.flipunlock.gesture.back",
        "persist.flipunlock.gesture.sf",
        "persist.flipunlock.rotation.fix",
        "persist.flipunlock.wallpaper.fix",
        "persist.flipunlock.display.state",
        "persist.flipunlock.display.keepon",
        "persist.flipunlock.display.keepon.block",
        "persist.flipunlock.single_outer",
        "persist.flipunlock.ui.lockscreen",
        "persist.flipunlock.ui.widget",
        "persist.flipunlock.ui.controlcenter",
        "persist.flipunlock.ui.recentsmenu",
        "persist.flipunlock.ui.qstilemin",
        "persist.flipunlock.ime",
        "persist.flipunlock.ime.navbar",
        "persist.flipunlock.camera",
        "persist.flipunlock.camera.fix",
        "persist.flipunlock.volume.keyremap",
        "persist.flipunlock.ui.keyguardfix",
        "persist.flipunlock.identity.tinyscreen",
        "persist.flipunlock.settings.imebottom",
        "persist.flipunlock.ui.launcherdensity",
        "persist.flipunlock.wallpaper.lock",
        "persist.flipunlock.wallpaper.identity",
        "persist.flipunlock.app.intercept",
        "persist.flipunlock.blur.probe",
        "persist.flipunlock.blur.stab",
    )

    // Master switch
    val enabled: Boolean get() = raw("persist.flipunlock.enable", true)

    // Display
    val displayDual: Boolean get() = enabled && raw("persist.flipunlock.display.dual", true)
    val displayAod: Boolean get() = enabled && raw("persist.flipunlock.display.aod", true)
    val displayCutout: Boolean get() = enabled && raw("persist.flipunlock.display.cutout", true)
    val displayFullscreen: Boolean get() = enabled && raw("persist.flipunlock.display.fullscreen", true)

    // App
    val appContinuity: Boolean get() = enabled && raw("persist.flipunlock.app.continuity", true)
    // AppInterceptUnlock(外屏启动拦截解除, **OS4.0.3+ 新版本专用通道**)开关
    //   2026-11-16 新增: 4.0.3 把 InterceptActivityController 改成由 MiuiStubRegistry.loadJar
    //   从 miui-appcompat.appcontinuity.jar 动态加载(不在主 classpath) → 老的
    //   param.classLoader.loadClass 必抛 ClassNotFoundException 被吞 = 静默空转。
    //   本开关只走新版本装载路径(loadJar/collectImpl 捕获 child ClassLoader), 老版本自然退回主 loader。
    //   双守护: flip2 + single_outer
    //   ★默认 **false**（2026-11-16 用户定案）: unlock2 走身份 1 → ContinuityPolicyService.sFolded
    //     恒 false → isInterceptList() 直接返回 false → 拦截本来就不发生，这里再 hook 是无意义的。
    //     真正需要它的是 **NoProp（属性 4，合盖时 sFolded=true）**，那边是常开。
    //     留着这条通道只是为了「身份回到 4」时能一键打开。
    val appIntercept: Boolean get() = enabled && raw("persist.flipunlock.app.intercept", false)
    // BlurProbe(模糊下发探针)开关 — 只统计+限流打印, 不改行为。默认 true(refMD §44.13 排查用)
    val blurProbe: Boolean get() = enabled && raw("persist.flipunlock.blur.probe", true)
    // BlurProbe 去重(stab)开关 — 同一 View 半径没变就不重复下发 setMiSelfBlur。
    //   默认 false: 先只探测; 打开后若闪烁消失, 即证明"每帧重复下发同一半径"是直接成因。
    val blurStab: Boolean get() = enabled && raw("persist.flipunlock.blur.stab", false)

    // Gesture — keep together
    val gestureHome: Boolean get() = enabled && raw("persist.flipunlock.gesture.home", true)
    val gestureBack: Boolean get() = enabled && raw("persist.flipunlock.gesture.back", true)
    // SFDeviceGestureHook(外屏上滑手势执行器)开关
    val gestureSf: Boolean get() = enabled && raw("persist.flipunlock.gesture.sf", true)
    // RotationFixHook(旋转解除)开关
    val rotationFix: Boolean get() = enabled && raw("persist.flipunlock.rotation.fix", true)
    // WallpaperFixHook(壁纸尺寸钳制, 修开机壁纸右侧黑)开关
    val wallpaperFix: Boolean get() = enabled && raw("persist.flipunlock.wallpaper.fix", true)
    // DisplayStateHook(DeviceState 钉死: 1b 恒布局 + getCurrentState)开关 — 高影响, 关可回退
    val displayState: Boolean get() = enabled && raw("persist.flipunlock.display.state", true)
    // DisplayKeepOnHook(单外屏保命: 阻止 display 0 幽灵内屏掉电)开关
    //   2026-11-15 新增: 拔内屏后框架一 disable display 0 就硬复位(refMD §10/§11)。
    //   默认关; 打开后先只记日志(block=false), 确认路径再把 block 打开。
    val displayKeepOn: Boolean get() = enabled && raw("persist.flipunlock.display.keepon", false)
    val displayKeepOnBlock: Boolean get() = displayKeepOn && raw("persist.flipunlock.display.keepon.block", false)
    // Single outer screen mode: the inner panel has been removed.
    // Tri-state:
    //   persist.flipunlock.single_outer = 1/0  -> explicit (the companion KSU module
    //                                             writes it right after boot)
    //   unset                                  -> auto-detect: the inner panel's
    //                                             synaptics touch input device is gone
    // Only this mode needs the SystemUI null-cutout defence (MIUI AOD plugin NPE),
    // and only this mode should be switched to device_state=CLOSED.
    val singleOuter: Boolean
        get() {
            if (!enabled) return false
            when (readProp("persist.flipunlock.single_outer").trim().lowercase()) {
                "1", "true" -> return true
                "0", "false" -> return false
            }
            // unreadable sysfs -> assume single outer (the crash we avoid is worse)
            return DeviceInfo.innerTouchPresent() != true
        }
    // CameraFixHook(相机进程属性→4, 修 flip 外屏相机倒置/黑边)开关
    val cameraFix: Boolean get() = enabled && raw("persist.flipunlock.camera.fix", true)
    // VolumeKeyRemapFixHook(音量键方向跟随旋转)开关
    val volumeKeyRemap: Boolean get() = enabled && raw("persist.flipunlock.volume.keyremap", true)
    // SystemUiKeyguardFix(崩溃环兜底)开关 — 默认 true; 关闭注意 systemui 崩溃环风险
    val keyguardFix: Boolean get() = enabled && raw("persist.flipunlock.ui.keyguardfix", true)
    // TinyScreenFixHook(属性层死角: getScreenType/isTinyScreen→false)开关
    val tinyScreenFix: Boolean get() = enabled && raw("persist.flipunlock.identity.tinyscreen", true)
    // LockWallpaperFixHook(锁屏壁纸被 ambient 隐藏 → 强制不隐藏)开关
    //   2026-09-24 新增: 修「锁屏没有壁纸 → 纯黑」(refMD §44.12)
    val lockWallpaperFix: Boolean get() = enabled && raw("persist.flipunlock.wallpaper.lock", true)
    // WallpaperIdentityFix(miwallpaper 进程内身份还原 → 修壁纸引擎崩溃环)开关
    //   2026-11-16 新增: 属性 1 + 单外屏下 MI_WALLPAPER_WHICH_ALL=3 只装载 which 1/2,
    //   而 WallpaperServiceController 仍初始化 4/8 的引擎 → t() 返回 null → NPE, 每 10s 一次。
    //   scope 必须勾选 com.miui.miwallpaper
    val wallpaperIdentity: Boolean get() = enabled && raw("persist.flipunlock.wallpaper.identity", true)
    // ImeBottomSupportHook(设置「全面屏键盘优化」入口: 国际版硬隐藏解除)开关
    //   2026-09-24 新增: ruyi_global Settings 反编译 — isMiuiImeBottomSupport() 的
    //   !IS_INTERNATIONAL_BUILD 使整块分类被 removePreference。需 scope 勾选 com.android.settings
    val settingsImeBottom: Boolean get() = enabled && raw("persist.flipunlock.settings.imebottom", true)
    // ImeNavBarFixHook(输入法底部 48dp 导航栏 ⌄/🌐 → 高度归 0)开关
    //   2026-09-25 新增: framework NavigationBarController$Impl 在 IME 窗口底部装 48dp NavigationBarFrame
    //   (dimen navigation_bar_frame_height)。需 scope 勾选输入法包(见 targetPackages)
    val imeNavBarFix: Boolean get() = enabled && raw("persist.flipunlock.ime.navbar", true)

    // UI
    val uiLockScreen: Boolean get() = enabled && raw("persist.flipunlock.ui.lockscreen", true)
    val uiWidget: Boolean get() = enabled && raw("persist.flipunlock.ui.widget", true)
    val uiControlCenter: Boolean get() = enabled && raw("persist.flipunlock.ui.controlcenter", true)
    val uiRecentsMenu: Boolean get() = enabled && raw("persist.flipunlock.ui.recentsmenu", true)
    // QSTileMinCountFixHook(控制中心编辑磁贴下限→0)开关
    val qsTileMinCount: Boolean get() = enabled && raw("persist.flipunlock.ui.qstilemin", true)
    // QSPanelWidthFixHook(横屏控制中心面板宽撑满屏幕)开关
    val qsPanelWidth: Boolean get() = enabled && raw("persist.flipunlock.ui.qspanelwidth", true)

    // Other
    val ime: Boolean get() = enabled && raw("persist.flipunlock.ime", true)

    // Disabled by default — broken / not needed
    val camera: Boolean get() = enabled && raw("persist.flipunlock.camera", false)
    val launcherDensity: Boolean get() = enabled && raw("persist.flipunlock.ui.launcherdensity", false)

    /** Print all toggle keys and values, plus coupling warnings. */
    fun logConfig() {
        val sb = StringBuilder("═══ FlipOuterUnlock Config ═══\n")
        for (key in keys) {
            sb.append("  $key = ${readProp(key)}\n")
        }
        // Coupling warnings
        if (gestureHome != gestureBack) {
            sb.append("  ⚠ gesture.home($gestureHome) ≠ gesture.back($gestureBack) — recommended keep together\n")
        }
        if (displayAod && !displayDual) {
            sb.append("  ⚠ display.aod=ON but display.dual=OFF — AOD may route to wrong display\n")
        }
        sb.append("  (getprop | grep persist.flipunlock)")
        log(sb.toString())
    }

    private fun raw(key: String, default: Boolean): Boolean {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!)
                .invoke(null, key, default) as? Boolean ?: default
        } catch (_: Exception) {
            default
        }
    }

    private fun readProp(key: String): String {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as? String ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
