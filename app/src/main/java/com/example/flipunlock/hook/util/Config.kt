package com.example.flipunlock.hook.util

/**
 * Feature toggles via SystemProperties。所有开关默认 true（除显式标注 false 的）。
 *
 * 查当前值：
 *   getprop | grep persist.flipunlock
 *
 * 常用开关（2026-11-23 清理后，已删功能的开关不再列）：
 *   persist.flipunlock.enable                 # 总开关
 *   persist.flipunlock.display.immersive      # 外屏状态栏沉浸
 *   persist.flipunlock.display.aod            # 外屏 AOD（flip1）
 *   persist.flipunlock.display.cutout         # 去挖孔
 *   persist.flipunlock.display.fullscreen     # 去 size-compat letterbox
 *   persist.flipunlock.rotation.fix           # 旋转解除
 *   persist.flipunlock.volume.keyremap        # 音量键跟随旋转
 *   persist.flipunlock.wallpaper.fix          # 壁纸尺寸钳制
 *   persist.flipunlock.wallpaper.lock         # 锁屏壁纸不被 ambient 隐藏
 *   persist.flipunlock.wallpaper.identity     # miwallpaper 身份还原
 *   persist.flipunlock.ui.qstilemin           # 控制中心磁贴下限
 *   persist.flipunlock.ui.qspanelwidth        # 横屏面板宽
 *   persist.flipunlock.ui.keyguardfix         # 锁屏面板兜底（flip1）
 *   persist.flipunlock.ui.flashlight          # 手电筒翻转提示直通
 *   persist.flipunlock.ime.navbar             # 输入法底部 48dp 归零（仅外屏）
 *   persist.flipunlock.camera.fix             # 相机进程属性→4
 *   persist.flipunlock.identity.tinyscreen    # getScreenType/isTinyScreen→false
 *   persist.flipunlock.settings.imebottom     # 设置「全面屏键盘优化」入口
 *   persist.flipunlock.app.intercept          # 外屏启动拦截解除（默认 false）
 *   persist.flipunlock.blur.probe / .stab     # 模糊探针 / 去重
 *   persist.flipunlock.single_outer           # 由 flip_prop 写入，单外屏模式
 *
 * 开关之间已无强耦合：display.dual / gesture.home|back 等「FOUNDATION 耦合」说明
 * 随对应功能删除一并作废（2026-11-23）。
 */
object Config {
    // 只列「当前有消费者」的开关。2026-11-23 清掉了对应功能已删的死开关
    // (display.dual / app.continuity / gesture.home|back / ui.lockscreen|widget|
    //  controlcenter|recentsmenu / ime / camera / ui.launcherdensity)。
    private val keys = listOf(
        "persist.flipunlock.enable",
        "persist.flipunlock.display.aod",
        "persist.flipunlock.aod.trace",
        "persist.flipunlock.aod.brightness",
        "persist.flipunlock.aod.state",
        "persist.flipunlock.display.cutout",
        "persist.flipunlock.display.fullscreen",
        "persist.flipunlock.display.immersive",
        "persist.flipunlock.display.state",
        "persist.flipunlock.gesture.sf",
        "persist.flipunlock.rotation.fix",
        "persist.flipunlock.wallpaper.fix",
        "persist.flipunlock.single_outer",
        "persist.flipunlock.ui.qstilemin",
        "persist.flipunlock.ui.qspanelwidth",
        "persist.flipunlock.ime.navbar",
        "persist.flipunlock.camera.fix",
        "persist.flipunlock.volume.keyremap",
        "persist.flipunlock.ui.keyguardfix",
        "persist.flipunlock.ui.flashlight",
        "persist.flipunlock.identity.tinyscreen",
        "persist.flipunlock.settings.imebottom",
        "persist.flipunlock.wallpaper.lock",
        "persist.flipunlock.wallpaper.identity",
        "persist.flipunlock.app.intercept",
        "persist.flipunlock.installer.swap",
        "persist.flipunlock.blur.probe",
        "persist.flipunlock.blur.stab",
        "persist.flipunlock.wallpaper.exact",
    )

    // Master switch
    val enabled: Boolean get() = raw("persist.flipunlock.enable", true)

    // Display
    val displayAod: Boolean get() = enabled && raw("persist.flipunlock.display.aod", true)
    // AOD 全链路探针（只打日志：doze 状态 / dream 生命周期 / 循环检测）。默认 true，纯诊断。
    val aodTrace: Boolean get() = enabled && raw("persist.flipunlock.aod.trace", true)
    // AOD 亮度下限（百分比 0..100）。0=不改（只记录）。
    //   2026-11-25：单外屏下 AOD 亮度档位恒为 i==5 → getDefaultLowBrightness()=1（≈5 nits），
    //   暗处可见、白天看不见；设 8 表示"AOD 至少 8% 亮度"。见 AodBrightnessHook / refMD §9。
    // 默认：单外屏给 10%（原生 5 nits 白天看不见）；双屏机器 0=不动
    val aodBrightnessFloor: Int get() = if (!enabled) 0 else readPropInt("persist.flipunlock.aod.brightness", if (singleOuter) 10 else 0)
    // AOD doze 状态覆盖：0=原生(4=DOZE_SUSPEND)，3=DOZE，2=ON。
    //   2026-11-25 单外屏实测：4 下面板 LPM 不刷新（AOD 只存在于合成层），3 是连续刷新。
    // 默认：单外屏给 2(ON)（4=DOZE_SUSPEND 在这块屏上进 LP1 自刷新=黑）；双屏机器 0=不动
    val aodDozeState: Int get() = if (!enabled) 0 else readPropInt("persist.flipunlock.aod.state", if (singleOuter) 2 else 0)
    val displayCutout: Boolean get() = enabled && raw("persist.flipunlock.display.cutout", true)
    val displayFullscreen: Boolean get() = enabled && raw("persist.flipunlock.display.fullscreen", true)
    // 外屏默认沉浸（状态栏 114px 让给应用）
    //   2026-11-23 用户定案：外屏高度宝贵，状态栏默认隐藏；下拉通知栏、顶部下滑临时呼出都保留。
    //   实现走 DisplayPolicy 的 forced-hide 通道（ImmersiveStatusBar.kt），不碰 policy_control。
    val displayImmersive: Boolean get() = enabled && raw("persist.flipunlock.display.immersive", true)
    // DisplayStateHook(按 device_state 分发布局)开关
    //   2026-11-24: 幽灵屏由 flip_prop 的布局表关掉后, 本 hook 对正常路径(CLOSED)已冗余,
    //   但 state 3(OPENED) 没有它会把锁屏/桌面渲染成放大版 -> 保留。
    val displayState: Boolean get() = enabled && raw("persist.flipunlock.display.state", true)

    // App
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
    // PackageInstallerSwap（系统默认安装器从 MIUI 版换成 AOSP 版）开关
    //   2026-11-25 新增: MIUI 安装器 coreApp+required 双堵，pm 卸不掉也禁不掉；
    //   本 hook 复刻 CTS 分支的效果（AOSP installed=true / MIUI 摘表 / required 字段挪走）
    //   + 路由层重解析。默认 **true**；设 false = 完全原生（重启生效）。
    //   规格：find/installer_swap_spec.md
    val installerSwap: Boolean get() = enabled && raw("persist.flipunlock.installer.swap", true)
    // BlurProbe(模糊下发探针)开关 — 只统计+限流打印, 不改行为。默认 true(refMD §44.13 排查用)
    val blurProbe: Boolean get() = enabled && raw("persist.flipunlock.blur.probe", true)
    // BlurProbe 去重(stab)开关 — 同一 View 半径没变就不重复下发 setMiSelfBlur。
    //   默认 false: 先只探测; 打开后若闪烁消失, 即证明"每帧重复下发同一半径"是直接成因。
    val blurStab: Boolean get() = enabled && raw("persist.flipunlock.blur.stab", false)
    // 壁纸尺寸「精确模式」—— 让 setDimensionHints 输出真实外屏尺寸 1208×1392（1:1，不 cover 缩放），
    //   而不是方形 1392×1392。用于验证「背景模糊每帧重算差异是否源于壁纸缩放」(refMD §44.13)。
    //   默认 false（保持既有方形行为）。
    val wallpaperExact: Boolean get() = enabled && raw("persist.flipunlock.wallpaper.exact", false)

    // Gesture
    // SFDeviceGestureHook(外屏上滑手势执行器)开关
    val gestureSf: Boolean get() = enabled && raw("persist.flipunlock.gesture.sf", true)
    // RotationFixHook(旋转解除)开关
    val rotationFix: Boolean get() = enabled && raw("persist.flipunlock.rotation.fix", true)
    // WallpaperFixHook(壁纸尺寸钳制, 修开机壁纸右侧黑)开关
    val wallpaperFix: Boolean get() = enabled && raw("persist.flipunlock.wallpaper.fix", true)
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
    // FlashlightHook(外屏手电筒"翻转提示"直通: setFlipListening→直接开关 + 跳过提示弹窗)开关
    //   2026-11-23 从「观察一期」转正: 它取代了旧的 getCurrentState→6 全局 hack
    val flashlightFix: Boolean get() = enabled && raw("persist.flipunlock.ui.flashlight", true)
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
    // QSTileMinCountFixHook(控制中心编辑磁贴下限→0)开关
    val qsTileMinCount: Boolean get() = enabled && raw("persist.flipunlock.ui.qstilemin", true)
    // QSPanelWidthFixHook(横屏控制中心面板宽撑满屏幕)开关
    val qsPanelWidth: Boolean get() = enabled && raw("persist.flipunlock.ui.qspanelwidth", true)

    /** Print all toggle keys and values. */
    fun logConfig() {
        val sb = StringBuilder("═══ FlipOuterUnlock Config ═══\n")
        for (key in keys) {
            sb.append("  $key = ${readProp(key)}\n")
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

    /** 读整数属性（读不到/格式错 → default）。 */
    private fun readPropInt(key: String, default: Int): Int {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("getInt", String::class.java, Int::class.javaPrimitiveType!!)
                .invoke(null, key, default) as? Int ?: default
        } catch (_: Exception) {
            default
        }
    }
}
