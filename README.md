# FlipOuterUnlock2 — MIX Flip Outer Screen Unlock Module

> LSPosed module for Xiaomi MIX Flip — make the outer screen behave like a normal phone display.

**One-liner**: Remove cutout, force fullscreen, unlock apps, spoof device identity, fix control center, fix Sogou IME, enable AOD on outer screen.

[English](#english) | [中文](#chinese)

---

<a name="english"></a>
## English

### Features

**Display & Fullscreen**
- Remove cutout — zero `CutoutSpecification.Parser` fields + defensive `Display.getCutout()` zero injection for camera process
- Force fullscreen — disable MIUI flip size-compat letterbox in system_server (`getFlipCompatModeByApp/Activity → 0`, `getFullScreenValue → 0`, `getGlobalScale → 1.0f`) + disable size-compat mode in app processes (`inMiuiSizeCompatScaleMode → false`, `getSizeCompatBounds → null`)
- Dual display — force display state=6

**Device Identity**
- Spoof device type — hook 7 detection groups: `MiuiMultiDisplayTypeInfo`, `miui.os.Build`, `miuix.os.Build` (incl. `IS_FOLD_INSIDE/OUTSIDE` static field clearing), `DeviceUtils`, `DeviceHelper`, `MiuiConfigs`, defensive static field clearing. Excludes SystemUI and Sogou IME
- Screen type spoof — `Configuration.getScreenType() → 0` (EXPAND), makes all processes believe they run on the primary screen

**App Management**
- Remove outer screen app launch restrictions
- System app whitelist
- App continuity — keep running app alive across fold/unfold

**IME & Input**
- Enable keyboard in landscape + suppress rotation toast
- Unlock IME choice — prevent forced Sogou switch on outer screen
- Sogou toolbar + clipboard fix (DexKit)

**SystemUI**
- Bypass flashlight flip-to-turn-on prompt
- Control center compact mode fix — restore QS tile editing
- Global `isTinyScreen→false` — fix modal menu, icon clipping, carrier text, control center layout
- Notification icon limit expansion — defense-in-depth for icon clipping

**fliphome**
- Widget overlay removal
- Recents cache refresh

**AOD**
- Always-On Display enabled on outer screen when folded

**Gestures**
- Double-tap-to-sleep on outer screen

### Hook Architecture

```
onSystemServerStarting (system_server):
├── AppRestriction          ← remove outer screen app restrictions
├── AppWhitelist            ← system app whitelist
├── CutoutRemove            ← remove cutout (Parser.parse + camera defense)
├── AppFullscreen (#1-#4)   ← fullscreen compat (system_server side)
├── AppContinuity           ← fold/unfold app continuity
├── InputMethodHook         ← IME rotation/choice unlock
├── SubScreenGesture        ← double-tap-to-sleep
└── DisplayState            ← force state=6 dual display

onPackageReady:
├── AppFullscreen.hookApp (#5-#6) ← app-side size-compat disable (excl. SystemUI, Sogou)
├── DeviceIdentityHook [*]        ← device identity spoof (7 hook groups, excl. SystemUI, Sogou)
├── ScreenTypeHook [*]            ← Configuration.getScreenType → 0 (EXPAND)
├── WidgetRemove [fliphome]       ← widget overlay removal
├── RecentsCacheFix [fliphome]    ← recents cache refresh
├── AodHook [aod]                 ← outer screen AOD enable
├── FlashlightHook [systemui]     ← flashlight flip prompt bypass
├── ControlCenterHook [systemui]  ← control center compact fix
├── StatusBarHook [systemui]      ← isTinyScreen→false + icon limit expansion
└── SogouInputHook [sogou]        ← Sogou toolbar + clipboard fix (DexKit)
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
| `persist.flipunlock.display.dual` | true | Dual display (DisplayState) |
| `persist.flipunlock.display.aod` | true | Outer screen AOD |
| `persist.flipunlock.display.cutout` | true | Remove cutout |
| `persist.flipunlock.display.fullscreen` | true | Force fullscreen (AppFullscreen) |
| `persist.flipunlock.app.continuity` | true | Fold/unfold continuity |
| `persist.flipunlock.ime` | true | IME freedom |
| `persist.flipunlock.ui.widget` | true | Widget overlay removal |
| `persist.flipunlock.ui.controlcenter` | true | Control center fix |

### LSP Scope

system, systemui, aod, camera, fliphome, sogou, miuihome, gallery

### Requirements

- LSPosed (libxposed API 101+)
- Xiaomi MIX Flip
- HyperOS

### Build

```bash
./gradlew :app:assembleDebug
```

CI: push to `master` branch triggers automatic build.

### Credits

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — LSPosed architecture, SogouHook, DexKit reference
- `refMD/cleaned/` — MIUI framework decompiled analysis docs

### License

AGPL-3.0

---

<a name="chinese"></a>
## 中文

### 功能

**显示与全屏**
- 移除挖孔 — `CutoutSpecification.Parser` 字段清零 + camera 进程防御性 `Display.getCutout()` 零值注入
- 强制全屏 — system_server 端禁用 MIUI flip size-compat letterbox（`getFlipCompatModeByApp/Activity → 0`、`getFullScreenValue → 0`、`getGlobalScale → 1.0f`）+ app 端关闭 size-compat 模式（`inMiuiSizeCompatScaleMode → false`、`getSizeCompatBounds → null`）
- 双屏显示 — 强制 display state=6

**设备身份**
- 伪装设备类型 — hook 7 组检测路径：`MiuiMultiDisplayTypeInfo`、`miui.os.Build`、`miuix.os.Build`（含 `IS_FOLD_INSIDE/OUTSIDE` 静态字段清除）、`DeviceUtils`、`DeviceHelper`、`MiuiConfigs`、防御性静态字段清除。排除 SystemUI 和 Sogou
- 伪装屏幕类型 — `Configuration.getScreenType() → 0`（EXPAND），让所有进程认为自己在主屏运行

**应用管理**
- 去除外屏应用启动限制
- 系统应用白名单
- 折叠续接控制 — 保持应用在折叠/展开时继续运行

**输入法**
- 横屏键盘启用 + 禁旋转提示
- 解除输入法锁定 — 阻止外屏强制切 Sogou
- Sogou 工具栏 + 剪贴板修复（DexKit）

**SystemUI**
- 手电筒翻转提示绕过
- 控制中心紧凑模式修复 — 恢复 QS tile 编辑功能
- 全局 `isTinyScreen→false` — 修复 modal 菜单、图标截断、运营商文本、控制中心布局
- 通知图标数量扩展 — 防御性兜底

**fliphome**
- 小部件覆盖层移除
- 最近任务缓存刷新

**AOD**
- 折叠状态下外屏 Always-On Display 启用

**手势**
- 外屏双击休眠

### Hook 架构

```
onSystemServerStarting (system_server):
├── AppRestriction          ← 去除外屏应用限制
├── AppWhitelist            ← 系统应用白名单
├── CutoutRemove            ← 去挖孔 (Parser.parse + camera 防御)
├── AppFullscreen (#1-#4)   ← 全屏兼容 (system_server 端)
├── AppContinuity           ← 折叠续接控制
├── InputMethodHook         ← IME 旋转/选择解锁
├── SubScreenGesture        ← 外屏双击休眠
└── DisplayState            ← 强制 state=6 双屏

onPackageReady:
├── AppFullscreen.hookApp (#5-#6) ← app 端 size-compat 关闭 (排除 SystemUI, Sogou)
├── DeviceIdentityHook [*]        ← 设备身份伪造 (7 hook 组, 排除 SystemUI, Sogou)
├── ScreenTypeHook [*]            ← Configuration.getScreenType → 0 (EXPAND)
├── WidgetRemove [fliphome]       ← 小部件覆盖移除
├── RecentsCacheFix [fliphome]    ← 最近任务缓存刷新
├── AodHook [aod]                 ← 外屏 AOD 启用
├── FlashlightHook [systemui]     ← 手电筒翻转提示绕过
├── ControlCenterHook [systemui]  ← 控制中心紧凑模式修复
├── StatusBarHook [systemui]      ← isTinyScreen→false + 沉浸式背景 + 图标数量扩展
└── SogouInputHook [sogou]        ← 搜狗 toolbar + 剪贴板修复 (DexKit)
```

### 功能开关

所有功能可通过 `setprop` 单独关闭，重启生效。无 UI。

```bash
# 查看当前设置
getprop | grep persist.flipunlock

# 关闭某个功能（示例）
setprop persist.flipunlock.display.cutout false
reboot
```

| 属性 | 默认 | 控制 |
|------|------|------|
| `persist.flipunlock.enable` | true | **总开关** |
| `persist.flipunlock.display.dual` | true | 双屏显示 (DisplayState) |
| `persist.flipunlock.display.aod` | true | 外屏 AOD |
| `persist.flipunlock.display.cutout` | true | 去除挖孔 |
| `persist.flipunlock.display.fullscreen` | true | 强制全屏 (AppFullscreen) |
| `persist.flipunlock.app.continuity` | true | 折叠续接 |
| `persist.flipunlock.ime` | true | 输入法自由切换 |
| `persist.flipunlock.ui.widget` | true | 小部件覆盖移除 |
| `persist.flipunlock.ui.controlcenter` | true | 控制中心修复 |

### LSP 作用域

system, systemui, aod, camera, fliphome, sogou, miuihome, gallery

### 要求

- LSPosed（libxposed API 101+）
- Xiaomi MIX Flip
- HyperOS

### 构建

```bash
./gradlew :app:assembleDebug
```

CI 自动构建：push 到 `master` 分支即触发。

### 致谢

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — LSPosed 架构、SogouHook、DexKit 参考
- `refMD/cleaned/` — MIUI 框架反编译分析文档

### License

AGPL-3.0
