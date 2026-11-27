# FlipOuterUnlock2 — MIX Flip Outer Screen Unlock Module

> LSPosed module for Xiaomi MIX Flip — make the outer screen behave like a normal phone display.
> 2026-08-22 审查更新: 功能清单/架构图/开关表对齐 Main.kt 实际注册状态。
> 2026-09-24 新增 `ImeBottomSupportHook`: 恢复设置「全面屏键盘优化」入口（国际版硬隐藏）。
> 2026-09-25 新增 `ImeNavBarFixHook`: 去掉输入法底部 48dp 导航栏（⌄/🌐），键盘贴底。

**One-liner**: Remove cutout, force fullscreen, unlock rotation, fix control center (tile editing + landscape width), fix camera/volume-key/wallpaper, keep outer-screen AOD.

[English](#english) | [中文](#chinese)

---

<a name="english"></a>
## English

### Features (currently registered in `Main.kt`)

**Display & Cutout** (system_server)
- Remove cutout — `CutoutSpecification.Parser` zeroing (flip2 only; flip1 covered by property layer) + camera-process non-null cutout defense
- Letterbox exemption — `Flip2CutoutLetterboxHook` (flip2 only)
- Display state routing — `DisplayStateHook`: state 3 (fully open) → native layout, other states → outer layout. **Kept on purpose** (2026-11-24): retiring it makes state 3 render zoomed on the outer screen (two independent boots, identical frame hash); see refMD §44.17.
- Default installer swap — `PackageInstallerSwap`: AOSP `com.android.packageinstaller` takes over from MIUI's (which collects info + forces its own security verification). State layer (`updateDefaultPkgInstallerLocked`: AOSP `setInstalled(true,0)` / MIUI unmapped / required fields moved) + routing layer (`hookChooseBestActivity`: re-resolve the APK VIEW intent to AOSP). Switch `persist.flipunlock.installer.swap` (default true). See refMD `Installer_Swap_AOSP.md`.

**Fullscreen & Rotation** (system_server)
- Force fullscreen — disable MIUI flip size-compat letterbox (`AppFullscreen`)
- Rotation unlock — `RotationFixHook` multi-layer (sensor enable + userRotation + `setOverrideOrientation`/`setOrientation` entry rewrite, portrait→USER_ROTATION)
- Volume key rotation direction — `VolumeKeyRemapFixHook` (restores the fold listener that the static `IS_FLIP_DEVICE` gate blocks + real fold/rotation drive; verified on ruyi_global)

**Wallpaper** (system_server)
- Wallpaper size clamping — `WallpaperFixHook` (flip1 right-side black / flip2 property-layer half-black background)

**Control Center** (systemui)
- QS tile edit minimum removal — `QSTileMinCountFixHook`: the minus badge is forced on both sides — view (`QSTileItemView.updateRemovable → true`) and record (`QSRecord.removable = true` written on the field after `QSListController` list changes, because `setRemovable` is R8-inlined on OS4) — plus AOSP/Compose fallbacks
- Landscape control center — `QSPanelWidthFixHook`: when the plugin's two panels (2×1052px + center gap) don't fit the screen, `CommonUtils.getInVerticalMode` is forced true (single panel = portrait layout) and the panel is widened to fill, so the fixed tiles (WiFi/media/brightness/volume) are no longer pushed off-screen

**SystemUI stability & identity**
- `SystemUiKeyguardFix` — TinyKeyguardPanel crash-loop guard (flip1 only)
- `TinyScreenFixHook` — `getScreenType→0` + `isTinyScreen/isFlipTinyScreen→false` (property-layer blind spots)
- `ImeBottomSupportHook` — restore Settings 「全面屏键盘优化」 entry (`settings`; hidden on international builds by `IS_INTERNATIONAL_BUILD`)

**IME** (input method processes)
- `ImeNavBarFixHook` — remove the 48dp IME bottom nav bar (`⌄`/`🌐`) so the keyboard sits flush against the bottom

**Camera** (camera)
- `CameraFixHook` — outer-screen camera upside-down + black bars (property-1 side effect, `multi_display_type→4`)
- `SelfieUnfoldMirrorHook` (2026-11-27) — the real cause of **mirrored videos** on the outer screen: the camera app always writes the session parameter `com.xiaomi.sessionparams.selfieUnfold = 1` (`z3.d.x()`; `K2.b.e0()` → `SECOND_SCREEN_MODE` on a single-outer-screen device), and the HAL then flips the whole session (preview **and** video). Photo mirroring is a separate path (`xiaomi.flip.enabled` / `isFrontMirror()`). The hook forces that session parameter to `0` (matched by the `ka.w0` B0 static-field identity, since `B0.b()` cannot be reflected at runtime) and pins `data.data.v.U()` → `true` to compensate (with `selfieUnfold=0` the HAL otherwise mirrors the still capture). Switch `persist.flipunlock.camera.no_selfie_unfold_mirror` (default **true**), `am force-stop com.android.camera` after changing. Verified on device: preview / video / photo all read correctly with `pref_front_mirror_boolean_key=false`. See refMD `Camera.md §13.10`.

**AOD** (aod)
- `AodHook` — outer-screen AOD when folded (flip1: framework + app side)
- `AodTraceHook` — doze state / dream lifecycle log + `aod.state` override (single-outer: 4→2 so the panel keeps scanning)
- `AodBrightnessHook` — AOD brightness log + floor (`aod.brightness`, single-outer default 10%)
- `AodFullAodPinHook` (system_server) — pin `updateFullAodModeLocked(true)` so PMS does not drop ON back to DOZE_SUSPEND after 5 s

**Gestures** (miuihome)
- `SFDeviceGestureHook` — outer-screen swipe-up gesture + gesture-disappear no-op guard

### Hook Architecture (aligned with Main.kt, 2026-09-25)

```
onSystemServerStarting (system_server):
├── CutoutRemove              (flip2)          ← remove cutout + camera defense
├── Flip2CutoutLetterboxHook  (flip2)          ← letterbox exemption
├── AppFullscreen                             ← size-compat disable
├── RotationFixHook                           ← rotation unlock (multi-layer)
├── VolumeKeyRemapFixHook                     ← volume key follows rotation
├── WallpaperFixHook                          ← wallpaper size clamp
└── AodHook.hookFramework      (flip1)        ← outer AOD

onPackageReady (app processes, one file = one feature):
├── SFDeviceGestureHook        [miuihome]     ← swipe-up gesture + gesture guard
├── SystemUiKeyguardFix        [systemui] (flip1) ← keyguard crash-loop guard
├── QSTileMinCountFixHook      [systemui]     ← QS edit minimum removal (plugin + AOSP)
├── QSPanelWidthFixHook        [systemui]     ← landscape panel width fill screen
├── AodHook                    [aod] (flip1)  ← outer AOD
├── TinyScreenFixHook          [identity]     ← getScreenType→0 / isTinyScreen→false
├── ImeBottomSupportHook       [settings]     ← restore 「全面屏键盘优化」 settings entry
├── ImeNavBarFixHook           [ime]          ← remove 48dp IME bottom nav bar (⌄/🌐)
└── CameraFixHook              [camera]       ← outer camera orientation + black bars

[OFF] 保留未注册(注释于 Main.kt, 便于恢复, HANDOFF §8):
  system_server: AppRestriction / AppContinuity / AppWhitelist / InputMethodHook / SubScreenGesture
  app: FlashlightHook / FlashlightStateHook / NotifFlipTipFixHook / NotifModalFixHook /
      Flip1AodIdentityHook / CameraCutoutFixHook / DeviceIdentityHook / ScreenTypeHook /
      WidgetRemove / RecentsCacheFix / WidgetTouchPassthrough / ControlCenterHook /
      StatusBarHook / SogouInputHook
```

### Feature Toggles

All features can be individually disabled via `setprop`. Changes take effect after reboot. No UI.

```bash
# List current settings
getprop | grep persist.flipunlock

# Disable a feature (example)
setprop persist.flipunlock.display.cutout false
reboot
```

| Property | Default | Controls |
|----------|---------|----------|
| `persist.flipunlock.enable` | true | **Master kill switch** |
| `persist.flipunlock.display.cutout` | true | Remove cutout (flip2) |
| `persist.flipunlock.display.fullscreen` | true | Force fullscreen |
| `persist.flipunlock.display.aod` | true | Outer-screen AOD |
| `persist.flipunlock.installer.swap` | true | Default installer -> AOSP (`PackageInstallerSwap`) |
| `persist.flipunlock.rotation.fix` | true | Rotation unlock |
| `persist.flipunlock.volume.keyremap` | true | Volume key rotation direction |
| `persist.flipunlock.wallpaper.fix` | true | Wallpaper size clamp |
| `persist.flipunlock.camera.fix` | true | Camera orientation/black-bars fix |
| `persist.flipunlock.ui.qstilemin` | true | QS tile edit minimum removal |
| `persist.flipunlock.ui.qspanelwidth` | true | Landscape panel width fill |
| `persist.flipunlock.ui.keyguardfix` | true | Keyguard crash-loop guard (flip1) |
| `persist.flipunlock.identity.tinyscreen` | true | getScreenType/isTinyScreen spoof |
| `persist.flipunlock.settings.imebottom` | true | Restore 「全面屏键盘优化」 settings entry |
| `persist.flipunlock.ime.navbar` | true | Remove IME bottom nav bar (⌄/🌐) |
| `persist.flipunlock.gesture.sf` | true | Gesture no-op guard (miuihome) |
| `persist.flipunlock.display.dual` | true | Dual display *(hook [OFF] 未注册)* |
| `persist.flipunlock.app.continuity` | true | Fold/unfold continuity *(hook [OFF])* |
| `persist.flipunlock.ui.controlcenter` | true | Control center compact fix *(hook [OFF])* |
| `persist.flipunlock.ui.widget` | true | Widget overlay removal *(hook [OFF])* |
| `persist.flipunlock.ime` | true | IME freedom *(hook [OFF])* |
| `persist.flipunlock.camera` | false | Legacy camera hook *(hook [OFF])* |
| `persist.flipunlock.ui.launcherdensity` | false | Launcher density *(hook [OFF])* |

### LSP Scope

system, systemui, aod, camera, fliphome, sogou, miuihome, gallery, settings + IME packages *(check manually — required by `ImeBottomSupportHook` / `ImeNavBarFixHook`)*

### Requirements

- LSPosed (libxposed API 101+)
- Xiaomi MIX Flip / MIX Flip 2
- HyperOS

### Build

```bash
./gradlew :app:assembleDebug
```

CI: push to `main` branch triggers automatic build.

### Credits

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — LSPosed architecture, plugin hook path (PluginFactory.createPluginContext), DexKit reference
- `refMD/cleaned/` — MIUI framework decompiled analysis docs

### License

AGPL-3.0

---

<a name="chinese"></a>
## 中文

### 功能（当前 Main.kt 实际注册）

**显示与挖孔**（system_server）
- 移除挖孔 — `CutoutSpecification.Parser` 清零（flip2；flip1 由属性层覆盖）+ camera 进程非 null cutout 防御
- letterbox 豁免 — `Flip2CutoutLetterboxHook`（flip2）

**全屏与旋转**（system_server）
- 强制全屏 — 禁用 MIUI flip size-compat letterbox（`AppFullscreen`）
- 旋转解锁 — `RotationFixHook` 多层（传感器启用 + userRotation + `setOverrideOrientation`/`setOrientation` 入口改写，portrait→USER_ROTATION）
- 音量键方向跟随旋转 — `VolumeKeyRemapFixHook`（补回被静态门 `IS_FLIP_DEVICE` 挡死的 fold 监听注册 + 真实折叠/旋转驱动；ruyi_global 实机验证 ✓）

**壁纸**（system_server）
- 壁纸尺寸钳制 — `WallpaperFixHook`（flip1 右侧黑 / flip2 属性层背景一半黑）

**控制中心**（systemui）
- 磁贴编辑下限解除 — `QSTileMinCountFixHook`：减号显示与点击两侧都兜——视图侧 `QSTileItemView.updateRemovable → true`，记录侧在 `QSListController` 列表变更后直接写 `QSRecord.removable = true`（OS4 上 `setRemovable` 被 R8 内联，setter hook 不生效）+ AOSP/Compose 兜底
- 横屏控制中心 — `QSPanelWidthFixHook`：插件双面板(2×1052px+中缝)放不下屏宽时，强制 `CommonUtils.getInVerticalMode`→true（退回单面板＝竖屏版式）并把面板撑满，固定磁贴(WiFi/媒体/亮度/音量)不再被挤出屏外

**SystemUI 稳定与身份**
- `SystemUiKeyguardFix` — TinyKeyguardPanel 崩溃环兜底（flip1）
- `TinyScreenFixHook` — `getScreenType→0` + `isTinyScreen/isFlipTinyScreen→false`（属性层死角）
- `ImeBottomSupportHook` — 恢复设置「全面屏键盘优化」入口（`settings`；国际版被 `IS_INTERNATIONAL_BUILD` 硬隐藏）

**输入法**（输入法进程）
- `ImeNavBarFixHook` — 去掉输入法底部 48dp 导航栏（`⌄` 隐藏键盘 / `🌐` 切换输入法），键盘贴底

**相机**（camera）
- `CameraFixHook` — 外屏相机倒置+黑边（属性1副作用，`multi_display_type→4`）
- `SelfieUnfoldMirrorHook`（2026-11-27）— **外屏录像镜像**的真因：相机总把 session 参数
  `com.xiaomi.sessionparams.selfieUnfold` 写成 `1`（`z3.d.x()`；单外屏下 `K2.b.e0()` 里的
  `SECOND_SCREEN_MODE` 恒 true），HAL 按它把**预览+录像整条 session** 翻；照片走另一条
  `xiaomi.flip.enabled`（`isFrontMirror()`）。本钩把该参数压成 `0`（按 `ka.w0` 的 B0 静态字段身份认
  `T`，因为运行时 `B0.b()` 反射拿不到），并把 `data.data.v.U()` 钉 `true` 抵消（否则 `selfieUnfold=0`
  后照片反而被 HAL 镜像）。开关 `persist.flipunlock.camera.no_selfie_unfold_mirror`（默认 **true**），
  改完 `am force-stop com.android.camera`。实机验证：`pref_front_mirror_boolean_key=false` 下
  预览/录像/照片三面均正读。见 refMD `Camera.md §13.10`。

**AOD**（aod，flip1）
- `AodHook` — 折叠时外屏 AOD（框架侧 + app 侧）

**手势**（miuihome）
- `SFDeviceGestureHook` — 外屏上滑手势 + 手势防消失 no-op

### Hook 架构（对齐 Main.kt，2026-09-25）

```
onSystemServerStarting (system_server):
├── CutoutRemove              (flip2)          ← 去挖孔 + camera 防御
├── Flip2CutoutLetterboxHook  (flip2)          ← letterbox 豁免
├── AppFullscreen                             ← size-compat 禁用
├── RotationFixHook                           ← 旋转解锁（多层）
├── VolumeKeyRemapFixHook                     ← 音量键跟随旋转
├── WallpaperFixHook                          ← 壁纸尺寸钳制
└── AodHook.hookFramework      (flip1)        ← 外屏 AOD

onPackageReady (app 进程, 一文件一功能):
├── SFDeviceGestureHook        [miuihome]     ← 上滑手势 + 手势防消失
├── SystemUiKeyguardFix        [systemui] (flip1) ← 锁屏崩溃兜底
├── QSTileMinCountFixHook      [systemui]     ← 磁贴编辑下限解除（插件 + AOSP）
├── QSPanelWidthFixHook        [systemui]     ← 横屏面板宽撑满
├── AodHook                    [aod] (flip1)  ← 外屏 AOD
├── TinyScreenFixHook          [identity]     ← getScreenType→0 / isTinyScreen→false
├── ImeBottomSupportHook       [settings]     ← 恢复「全面屏键盘优化」设置入口
├── ImeNavBarFixHook           [ime]          ← 去掉输入法底部 48dp 导航栏(⌄/🌐)
└── CameraFixHook              [camera]       ← 外屏相机方向 + 黑边

[OFF] 保留未注册（Main.kt 注释, 便于恢复, HANDOFF §8）:
  system_server: AppRestriction / AppContinuity / AppWhitelist / InputMethodHook / SubScreenGesture
  app: FlashlightHook / FlashlightStateHook / NotifFlipTipFixHook / NotifModalFixHook /
      Flip1AodIdentityHook / CameraCutoutFixHook / DeviceIdentityHook / ScreenTypeHook /
      WidgetRemove / RecentsCacheFix / WidgetTouchPassthrough / ControlCenterHook /
      StatusBarHook / SogouInputHook
```

### 功能开关

所有功能可通过 `setprop` 单独关闭，重启生效。无 UI。

```bash
# 查看当前设置
getprop | grep persist.flipunlock

# 关闭某功能（示例）
setprop persist.flipunlock.display.cutout false
reboot
```

| 属性 | 默认 | 控制 |
|------|------|------|
| `persist.flipunlock.enable` | true | **总开关** |
| `persist.flipunlock.display.cutout` | true | 去挖孔（flip2） |
| `persist.flipunlock.display.fullscreen` | true | 强制全屏 |
| `persist.flipunlock.display.aod` | true | 外屏 AOD |
| `persist.flipunlock.installer.swap` | true | Default installer -> AOSP (`PackageInstallerSwap`) |
| `persist.flipunlock.rotation.fix` | true | 旋转解锁 |
| `persist.flipunlock.volume.keyremap` | true | 音量键方向跟随旋转 |
| `persist.flipunlock.wallpaper.fix` | true | 壁纸尺寸钳制 |
| `persist.flipunlock.camera.fix` | true | 相机方向/黑边修复 |
| `persist.flipunlock.ui.qstilemin` | true | 磁贴编辑下限解除 |
| `persist.flipunlock.ui.qspanelwidth` | true | 横屏面板宽撑满 |
| `persist.flipunlock.ui.keyguardfix` | true | 锁屏崩溃兜底（flip1） |
| `persist.flipunlock.identity.tinyscreen` | true | getScreenType/isTinyScreen 伪装 |
| `persist.flipunlock.settings.imebottom` | true | 恢复设置「全面屏键盘优化」入口 |
| `persist.flipunlock.ime.navbar` | true | 去掉输入法底部 48dp 导航栏（⌄/🌐） |
| `persist.flipunlock.gesture.sf` | true | 手势防消失（miuihome） |
| `persist.flipunlock.display.dual` | true | 双屏显示 *(hook [OFF] 未注册)* |
| `persist.flipunlock.app.continuity` | true | 折叠续接 *(hook [OFF])* |
| `persist.flipunlock.ui.controlcenter` | true | 控制中心紧凑修复 *(hook [OFF])* |
| `persist.flipunlock.ui.widget` | true | 小部件移除 *(hook [OFF])* |
| `persist.flipunlock.ime` | true | 输入法自由 *(hook [OFF])* |
| `persist.flipunlock.camera` | false | 旧相机 hook *(hook [OFF])* |
| `persist.flipunlock.ui.launcherdensity` | false | 桌面密度 *(hook [OFF])* |

### LSP Scope

system, systemui, aod, camera, fliphome, sogou, miuihome, gallery, settings + 输入法包 *(需手动勾选，`ImeBottomSupportHook` / `ImeNavBarFixHook` 依赖)*

### 依赖

- LSPosed（libxposed API 101+）
- 小米 MIX Flip / MIX Flip 2
- HyperOS

### 构建

```bash
./gradlew :app:assembleDebug
```

CI: 推送到 `main` 分支自动构建。

### 致谢

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — LSPosed 架构、插件 hook 路径（PluginFactory.createPluginContext）、DexKit 参考
- `refMD/cleaned/` — MIUI 框架反编译分析文档

### License

AGPL-3.0
