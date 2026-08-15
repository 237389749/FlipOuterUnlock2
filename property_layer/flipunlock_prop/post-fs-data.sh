#!/system/bin/sh
# FlipUnlock 属性层（flip2/bixi 专用，KSU/Magisk 模块 post-fs-data）
# 在 zygote fork 前改属性 → boot classpath 类的静态常量
# （miuix.os.Build.IS_FLIP / DeviceFeature.IS_FOLD_DEVICE 等）初始化时读到改后的值，
# 连 LSP hook 覆盖不了的"身份死区"一起解决。
#
# 部署：本目录复制到 /data/adb/modules/flipunlock_prop/ 后重启。
# 卸载：删除目录重启即可（不改分区，可回滚）。
#
# 注意：flip2 的 SystemUI 有 Dummy 保护（refMD §38.2：flip2 ViewController 构造
# isFlipDevice?Impl:Dummy），属性 4→1 后 SystemUI 走 Dummy 不崩（区别于 flip1，
# flip1 需 SystemUiKeyguardFix 兜底）。

# 核心：flip(4) → 普通手机(1)
# isFlipDevice/isFoldDevice 全 false + 静态常量全变
resetprop persist.sys.multi_display_type 1

# 可选：关闭"继续到内屏"对话框提示（flip2 上该属性为 true）
resetprop ro.config.miui_dialogcontinuity_enable false

# 注意：不要动 persist.sys.muiltdisplay_type（typo，==2 是横折叠判定，
# flip 是竖折叠，激活横折叠路径会引入错误行为）。
