#!/system/bin/sh
# FlipUnlock 属性层 (flip2/bixi)
# 核心: flip(4)->普通手机(1), 静态常量全变
resetprop persist.sys.multi_display_type 1

# 注意: flip2 未实测 CWB 崩溃, 暂不带 CWB 保护开关。
# 若 flip2 属性 1 下出现 composer 崩溃(ValidateCwbConfigInfo/ConfigureCwbForIdleFallback),
# 取消下面两行注释启用保护:
# resetprop -n vendor.display.disable_cwb_call 1
# resetprop -n vendor.display.enable_allow_idle_fallback 0

# flip2 全屏(DISPLAY_CUTOUT letterbox)与属性无关, 需 LSP hook
# (Flip2CutoutLetterboxHook: WindowStateStubImpl.isMiuiLayoutInCutoutAlways→true, refMD §34.3)
