# property_layer/ — KSU/Magisk 属性层模块

## 内容

| 路径 | 说明 |
|---|---|
| `flip_prop/` | **源码（唯一真源，git 跟踪）**：`module.prop` + `customize.sh` + `post-fs-data.sh` + `service.sh` + `uninstall.sh` |

> ⚠️ **产物（`*.zip`）不入库**：安装包请自行打包（下一节），或用 CI 在 **tag** 上产出的 Release 资产
> `flipunlock_prop.zip`（见「发版时 CI 会自动打包」）。仓库里只留可复现的源码。

## 重新打包

```powershell
# 在仓库根目录执行（zip 里必须是文件在根，不能嵌套 flip_prop/ 目录）
Compress-Archive -Path property_layer\flip_prop\* -DestinationPath flip_prop.zip -Force
# 校验：列出的应是 5 个文件、无目录前缀
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::OpenRead((Resolve-Path flip_prop.zip)).Entries |
  Select-Object FullName, Length
```

## 发版时 CI 会自动打包（推荐路径）

`.github/workflows/ci_build.yml` 里两个只在 **tag** 上执行的步骤：

```yaml
- name: Package property-layer module (zip, Magisk/KSU flashable)
  if: github.ref_type == 'tag'
  run: (cd property_layer/flip_prop && zip -r ../../flipunlock_prop.zip .)

- name: Create Release
  if: github.ref_type == 'tag'
  # 资产 = APK + flipunlock_prop.zip
```

⇒ **打 tag（如 `v3.1`）会同时产出「带新 hook 的 APK + 该 tag 对应的 prop zip」Release**，
所以仓库里不需要再提交一份 zip。

## 安装（设备端）

```bash
# 先自行打包（见上）或直接用 Release 里的 flipunlock_prop.zip
adb push flip_prop.zip /data/local/tmp/
MSYS_NO_PATHCONV=1 adb shell su -c '/data/adb/ksud module install /data/local/tmp/flip_prop.zip'
# 或 KSU/Magisk Manager → 从本地安装 → 选这个 zip
# ⚠️ zip 的 module.prop 必须在压缩包根目录，不能嵌套 flip_prop/ 文件夹
```

## 与 LSP 模块的配对关系（重要）

- **属性 1 + 无 LSP** = SystemUI `TinyKeyguardPanel` NPE 崩溃环（refMD FoldState §38）
  → `service.sh` 已内置"检测到 LSP 未安装则属性还原 4"，`customize.sh` 也会在安装时拦截。
- **属性 1 + 无 CWB 保护** = composer CWB 崩溃（refMD `CWB_Inject_Breakage.md`）
  → `post-fs-data.sh` 对 flip1 设 `vendor.display.disable_cwb_call=1`。
- 正确组合：**KSU 属性模块 + LSP 模块 + 身份排除 + 全 scope**，或**都不装**（属性 4 原生）。
