# CLAUDE.md — FlipOuterUnlock2 (Rewrite)

## 0. Hook 添加工作流（强制）

**每次添加任何 hook 之前，必须执行以下流程：**

```
┌─────────────────────────────────────────────────────────┐
│ 1. 查 refMD：该功能是否有完整逻辑链？                      │
│    ├─ YES → 取最上游 hook 点 + 参考旧项目写法              │
│    └─ NO  → 开启 agent 去 FlipRes 中补全逻辑链            │
│             → 更新到 refMD/cleaned/ 对应文件               │
│             → 然后再实现 hook                             │
│                                                         │
│ 2. 确认 hook 点在哪个进程（system_server / app pkg）       │
│                                                         │
│ 3. 实现：一个文件 = 一个完整功能 = 加入即生效              │
│                                                         │
│ 4. 验证：推 CI → 安装 → 设备测试 → 确认功能正常           │
└─────────────────────────────────────────────────────────┘
```

**绝对禁止**：
- 未查 refMD 就写 hook
- 在下游补防御性 hook 而上游未切
- 一个文件依赖另一个文件的隐式副作用

## 1. 项目结构

```
hook/
├── util/                    ← 框架工具（不含 hook 实现）
│   ├── Config.kt            ← persist.flipunlock.* 开关
│   ├── HookUtils.kt         ← hook/replaceResult/before/after
│   ├── ReflectUtils.kt      ← method/field/callMethod
│   ├── ScreenUtils.kt       ← 内外屏识别（分辨率+density）
│   └── Exclusions.kt        ← 排除列表
├── BaseHook.kt              ← 抽象基类
│
├── system_server/           ← onSystemServerStarting 加载
│   ├── AppRestriction.kt    ← 去除外屏应用限制
│   ├── DisplayState.kt      ← 强制 state=6 双屏
│   ├── Cutout.kt            ← 去挖孔（上游 Parser）
│   └── ...
│
├── com.miui.home/           ← miuihome 进程
│   ├── BottomGesture.kt
│   └── ...
│
├── com.miui.fliphome/       ← fliphome 进程
│   └── BackGesture.kt
│
├── com.android.systemui/    ← SystemUI 进程
│   └── ...
│
└── <package_name>/          ← 按需添加
```

**规则**：目录名 = 目标包名（system_server 除外）。`onPackageReady(pkg)` 时加载对应目录下所有文件。

## 2. 设计原则

### 单文件完整功能
- 加入 AodHook → AOD 就能用
- 移除 AodHook → AOD 就关闭
- 不存在"需要同时加入 X 才能工作"

### 上游优先
- 从逻辑链最上游切入
- 下游自然正确则不加 hook
- 只在测试发现泄漏时才补防御

### 内外屏识别
- 统一使用 `ScreenUtils.identify(width, height, density)`
- 不硬编码 displayId 或分辨率数字

### 进程正确性
- system_server hook 影响窗口布局（WMS/DisplayPolicy）
- app 进程 hook 只影响该进程内的类
- 注意 FIELD 直接访问不经过 getter hook

## 3. 仓库地图

| 路径 | 用途 |
|------|------|
| `FlipOuterUnlock2/` | 新项目源码（本仓库） |
| `FlipOuterUnlock/` | 旧项目（参考写法，不再修改） |
| `FlipRes/` | 反编译 MIUI framework/services/APK（只读） |
| `refMD/cleaned/` | 逻辑链文档（hook 前必查） |

## 4. 构建与 CI

- **不在本地编译**。推到 GitHub，CI 自动构建。
- Token: 项目根目录 `ghp_*.txt`
- gradlew 必须保持 +x 权限（`git update-index --chmod=+x gradlew`）
- 包名：`com.example.flipunlock`（与旧项目相同）

## 5. 诊断工作流

```
adb logcat -s FlipOuterUnlock:E
```

出 bug 时：
1. 查 refMD 对应逻辑链
2. 在 FlipRes 中确认实际代码路径
3. 添加诊断日志（不是 hook）确认值
4. 确认后再实现修复

## 6. refMD 文件索引

| 文件 | 覆盖内容 |
|------|----------|
| `INDEX.md` | 总索引、hook 覆盖矩阵 |
| `Hook_Chain_Map.md` | 所有 hook 上下游链 |
| `DisplayCutout.md` | 挖孔完整链（8 入口、computeFrames、toast） |
| `FoldState_Device_Identity.md` | 设备身份、display state、AOD |
| `Gesture_Widget_Overlay.md` | 手势、widget overlay、7-gate、provision |
| `IME_Restrictions.md` | 输入法限制 |
| `LockScreen.md` | 锁屏面板链 |
| `Lessons_Learned.md` | 踩坑记录 |
