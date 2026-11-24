# property_layer/ — KSU/Magisk 配套模块（属性层 + flip2 触摸补丁 + 单外屏自动化）

## 内容

| 路径 | 说明 |
|---|---|
| `flip_prop/` | **源码（唯一真源，git 跟踪）**：`module.prop` + `customize.sh` + `post-fs-data.sh` + `service.sh` + `uninstall.sh` |
| `flip_prop/synaptics_tcm2_patched.ko` | **不入库**（937 KB 二进制）。打包前从 `out/ko/os403/synaptics_tcm2_patched.ko` 拷进来，否则 flip2 的触摸补丁不生效（`customize.sh` 会提示） |

## 它到底做了什么

`post-fs-data.sh`（开机最早阶段，root）：

1. `panic_on_oops=0` —— 兜底，任何 oops 不直接硬复位；
2. `resetprop persist.sys.multi_display_type 1` —— 身份 4（flip 原生）→ 1（普通手机），**LSPosed 模块的整套 hook 依赖这个**；
3. **flip2 单外屏：把 `outer_only_layout.xml` 写到 `/data/system/displayconfig/display_layout_configuration.xml`**
   —— 面板拆掉后 DSI 认不到面板（`dmesg`：`panel_recognize_err` / `mi,panel-id not specified`），
   驱动回落到一个默认 mode，框架于是多出**第二块 display（1440×3200，不是本机内屏的 1224×2912）**；
   它与主屏同 display group、每帧参与合成，逼着 SurfaceFlinger 的 PassBlur 按两个 viewport 各重建一次，
   主屏交替读到内屏居中裁切的那份结果 → **控制中心 / 大文件夹闪烁**（详见 refMD `FoldState_Device_Identity.md` §44.14 H/K）。
   开关 `persist.flipunlock.outer.disable_inner`（默认 1）；
   地址模板按 OS4.0.3 实测写死，`service.sh` 发现地址漂移会按真实值重写（下次开机生效）；
4. flip1（`ro.product.model=2405*`）设 CWB 保护属性；
5. **flip2（`ro.product.device=bixi`）换 `synaptics_tcm2.ko`**：拔掉内屏后 `goodix_ts_resume` 会调
   `get_capfold_information`，内屏不在 → 全局上下文 NULL → oops → 硬复位。补丁把函数入口改成
   `mov w0,#-19; ret`，调用方只记一条 `get capfold information failed`，随后正常 resume。
   **带哈希闸门**：`EXPECTED_SHA` 必须匹配当前 ROM 的 stock 模块；OTA 后不匹配就**跳过换模块**
   （触摸保持原厂可用），日志写 `/data/local/tmp/flip_prop_postfs.log`，
   重打用 `out/tools/repatch_touch.py`。

`service.sh`（`boot_completed` 之后）：

1. 出厂重置时跳过引导向导（`device_provisioned` / `user_setup_complete`）；
2. **LSPosed 模块没装 → 身份还原 4**（光装属性层会让 SystemUI 崩溃环）；
3. SystemUI 崩溃自愈：1 分钟内崩 3 次 → 身份还原 4 + 重启 SystemUI；
4. **flip2 单外屏**：`cmd device_state state 0`（外屏变 logical display 0）→ 属性 1 时再
   `wm fixed-to-user-rotation enabled` 钉竖屏。开关：`persist.flipunlock.outer.force_closed`
   （默认 1；设 0 = 不切，布局表照样保证外屏是主屏）。
   **v7 起不再死等 `uptime ≥ 60`**：outer_only 布局表把每个 state 都映射到外屏后，拓扑不再依赖时序
   （实测停在 HALF_OPENED 也能正常出图、旋转正常），所以改成"尽快切 + 失败重试"，生效窗口从 60s 压到几秒；
5. **布局表自检**：从 `dumpsys display` 读真实 outer / inner 地址，与已装的表比对；
   地址漂移（换 ROM）就按真实值重写，日志写明"下次开机生效"。

`uninstall.sh`：`multi_display_type` 写回 4（persist 属性不会自己消失），清掉 CWB 运行时属性、
单外屏开关，并**删掉 `/data/system/displayconfig/display_layout_configuration.xml`**
（否则幽灵屏会一直保持被禁用；MIUI 自己的 `display_cloud_backup.xml` 不动）。

## 重新打包

```powershell
# 1) 把补丁模块拷进包里（不入库，必须手动放）
Copy-Item ..\..\out\ko\os403\synaptics_tcm2_patched.ko property_layer\flip_prop\ -Force

# 2) 打包（zip 里必须是文件在根，不能嵌套 flip_prop/ 目录）
Compress-Archive -Path property_layer\flip_prop\* -DestinationPath flip_prop.zip -Force
```

> ⚠️ Windows 上 `core.autocrlf` 会把 `.sh` checkout 成 CRLF，打出来的 zip 在设备上会
> `syntax error`。仓库根已有 `.gitattributes`（`*.sh text eol=lf`）；打包前仍建议确认 zip 里是 LF。

## 安装（设备端）

```bash
adb push flip_prop.zip /data/local/tmp/
adb shell /data/adb/ksud module install /data/local/tmp/flip_prop.zip
adb reboot
```

## 与 LSPosed 模块的配对关系（重要）

- **属性 1 + 无 LSP** = SystemUI `TinyKeyguardPanel` NPE 崩溃环（refMD FoldState §38）
  → `service.sh` 有"未装则还原 4"和"崩溃自愈"两道保险，`customize.sh` 安装时也会拦。
- **属性 1 + 无 CWB 保护**（flip1）= composer CWB 崩溃 → `post-fs-data.sh` 对 flip1 设保护。
- 正确组合：**KSU 配套模块 + LSPosed 模块 + scope 全勾**，或**都不装**（属性 4 原生）。

## 版本

- v4（2026-11-16）：并入 flip2 触摸补丁（OS4.0.3 哈希 `04ae4874…`）与单外屏自动切换；
  新增 `persist.flipunlock.outer.force_closed` 开关；`uninstall.sh` 清理该开关。
