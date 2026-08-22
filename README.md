# FlipOuterUnlock2 — MIX Flip Outer Screen Unlock Module

> LSPosed module for Xiaomi MIX Flip — make the outer screen behave like a normal phone display.
> 2026-08-22 审查更新: 功能清单/架构图/开关表对齐 Main.kt 实际注册状态。

**One-liner**: Remove cutout, force fullscreen, unlock rotation, fix control center (tile editing + landscape width), fix camera/volume-key/wallpaper, keep outer-screen AOD.

[English](#english) | [中文](#chinese)

---

<a name="english"></a>
## English

### Features (currently registered in `Main.kt`)

**Display & Cutout** (system_server)
- Remove cutout — `CutoutSpecification.Parser` zeroing (flip2 only; flip1 covered by property layer) + camera-process non-null cutout defense
- Letterbox exemption — `Flip2CutoutLetterboxHook` (flip2 only)
- Display state routing — `DisplayStateHook`: state 3 (fully open) → native inner layout; all other states → outer screen on (0/1/2/4...)

**Fullscreen & Rotation** (system_server)
- Force fullscreen — disable MIUI flip size-compat letterbox (`AppFullscreen`)
- Rotation unlock — `RotationFixHook` multi-layer (sensor enable + userRotation + `setOverrideOrientation`/`setOrientation` entry rewrite, portrait→USER_ROTATION)
- Volume key rotation direction — `VolumeKeyRemapFixHook` (fold-state init + notify sync)

**Wallpaper** (system_server)
- Wallpaper size clamping — `WallpaperFixHook` (flip1 right-side black / flip2 property-layer half-black background)

**Control Center** (systemui)
- QS tile edit minimum removal — `QSTileMinCountFixHook`: plugin `QSRecord.setRemovable → true` (landscape/inner-style VERTICAL control center) + AOSP/Compose fallbacks
- Landscape tile layout width — `QSPanelWidthFixHook`: `MainPanelController.updatePanelWidth` → HORIZONTAL panels fill screen width

**SystemUI stability & identity**
- `SystemUiKeyguardFix` — TinyKeyguardPanel crash-loop guard (flip1 only)
- `TinyScreenFixHook` — `getScreenType→0` + `isTinyScreen/isFlipTinyScreen→false` (property-layer blind spots)

**Camera** (camera)
- `CameraFixHook` — outer-screen camera upside-down + black bars (property-1 side effect, `multi_display_type→4`)

**AOD** (aod, flip1 only)
- `AodHook` — outer-screen AOD when folded (framework + app side)

**Gestures** (miuihome)
- `SFDeviceGestureHook` — outer-screen swipe-up gesture + gesture-disappear no-op guard

### Hook Architecture (aligned with Main.kt, 2026-08-22)

```
onSystemServerStarting (system_server):
├── CutoutRemove              (flip2)          ← remove cutout + camera defense
├── Flip2CutoutLetterboxHook  (flip2)          ← letterbox exemption
├── DisplayStateHook                          ← display-state branch (outer on unless fully open)
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
| `persist.flipunlock.display.state` | true | DisplayStateHook |
| `persist.flipunlock.display.aod` | true | Outer-screen AOD |
| `persist.flipunlock.rotation.fix` | true | Rotation unlock |
| `persist.flipunlock.volume.keyremap` | true | Volume key rotation direction |
| `persist.flipunlock.wallpaper.fix` | true | Wallpaper size clamp |
| `persist.flipunlock.camera.fix` | true | Camera orientation/black-bars fix |
| `persist.flipunlock.ui.qstilemin` | true | QS tile edit minimum removal |
| `persist.flipunlock.ui.qspanelwidth` | true | Landscape panel width fill |
| `persist.flipunlock.ui.keyguardfix` | true | Keyguard crash-loop guard (flip1) |
| `persist.flipunlock.identity.tinyscreen` | true | getScreenType/isTinyScreen spoof |
| `persist.flipunlock.gesture.sf` | true | Gesture no-op guard (miuihome) |
| `persist.flipunlock.display.dual` | true | Dual display *(hook [OFF] 未注册)* |
| `persist.flipunlock.app.continuity` | true | Fold/unfold continuity *(hook [OFF])* |
| `persist.flipunlock.ui.controlcenter` | true | Control center compact fix *(hook [OFF])* |
| `persist.flipunlock.ui.widget` | true | Widget overlay removal *(hook [OFF])* |
| `persist.flipunlock.ime` | true | IME freedom *(hook [OFF])* |
| `persist.flipunlock.camera` | false | Legacy camera hook *(hook [OFF])* |
| `persist.flipunlock.ui.launcherdensity` | false | Launcher density *(hook [OFF])* |

### LSP Scope

system, systemui, aod, camera, fliphome, sogou, miuihome, gallery

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
- 显示状态路由 — `DisplayStateHook`：全展开(state 3)走原生内屏布局，其余状态(0/1/2/4…)外屏亮

**全屏与旋转**（system_server）
- 强制全屏 — 禁用 MIUI flip size-compat letterbox（`AppFullscreen`）
- 旋转解锁 — `RotationFixHook` 多层（传感器启用 + userRotation + `setOverrideOrientation`/`setOrientation` 入口改写，portrait→USER_ROTATION）
- 音量键方向跟随旋转 — `VolumeKeyRemapFixHook`（折叠态初始化 + notify 同步）

**壁纸**（system_server）
- 壁纸尺寸钳制 — `WallpaperFixHook`（flip1 右侧黑 / flip2 属性层背景一半黑）

**控制中心**（systemui）
- 磁贴编辑下限解除 — `QSTileMinCountFixHook`：插件 `QSRecord.setRemovable → true`（内屏样式 VERTICAL 控制中心）+ AOSP/Compose 兜底
- 横屏磁贴布局撑满 — `QSPanelWidthFixHook`：`MainPanelController.updatePanelWidth` → HORIZONTAL 双面板撑满屏宽

**SystemUI 稳定与身份**
- `SystemUiKeyguardFix` — TinyKeyguardPanel 崩溃环兜底（flip1）
- `TinyScreenFixHook` — `getScreenType→0` + `isTinyScreen/isFlipTinyScreen→false`（属性层死角）

**相机**（camera）
- `CameraFixHook` — 外屏相机倒置+黑边（属性1副作用，`multi_display_type→4`）

**AOD**（aod，flip1）
- `AodHook` — 折叠时外屏 AOD（框架侧 + app 侧）

**手势**（miuihome）
- `SFDeviceGestureHook` — 外屏上滑手势 + 手势防消失 no-op

### Hook 架构（对齐 Main.kt，2026-08-22）

```
onSystemServerStarting (system_server):
├── CutoutRemove              (flip2)          ← 去挖孔 + camera 防御
├── Flip2CutoutLetterboxHook  (flip2)          ← letterbox 豁免
├── DisplayStateHook                          ← 按 state 分支外屏亮
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
| `persist.flipunlock.display.state` | true | DisplayStateHook |
| `persist.flipunlock.display.aod` | true | 外屏 AOD |
| `persist.flipunlock.rotation.fix` | true | 旋转解锁 |
| `persist.flipunlock.volume.keyremap` | true | 音量键方向跟随旋转 |
| `persist.flipunlock.wallpaper.fix` | true | 壁纸尺寸钳制 |
| `persist.flipunlock.camera.fix` | true | 相机方向/黑边修复 |
| `persist.flipunlock.ui.qstilemin` | true | 磁贴编辑下限解除 |
| `persist.flipunlock.ui.qspanelwidth` | true | 横屏面板宽撑满 |
| `persist.flipunlock.ui.keyguardfix` | true | 锁屏崩溃兜底（flip1） |
| `persist.flipunlock.identity.tinyscreen` | true | getScreenType/isTinyScreen 伪装 |
| `persist.flipunlock.gesture.sf` | true | 手势防消失（miuihome） |
| `persist.flipunlock.display.dual` | true | 双屏显示 *(hook [OFF] 未注册)* |
| `persist.flipunlock.app.continuity` | true | 折叠续接 *(hook [OFF])* |
| `persist.flipunlock.ui.controlcenter` | true | 控制中心紧凑修复 *(hook [OFF])* |
| `persist.flipunlock.ui.widget` | true | 小部件移除 *(hook [OFF])* |
| `persist.flipunlock.ime` | true | 输入法自由 *(hook [OFF])* |
| `persist.flipunlock.camera` | false | 旧相机 hook *(hook [OFF])* |
| `persist.flipunlock.ui.launcherdensity` | false | 桌面密度 *(hook [OFF])* |

### LSP Scope

system, systemui, aod, camera, fliphome, sogou, miuihome, gallery

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
