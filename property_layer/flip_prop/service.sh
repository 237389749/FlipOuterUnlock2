#!/system/bin/sh
# FlipUnlock 属性层 (flip1/flip2 通用) — 跳过开机引导(provision)保护
# 2026-08-14: 若设备未引导(device_provisioned≠1, 如恢复出厂/换机后首次开机),
# 标记跳过引导向导, 直接进桌面(测试/二手机场景; 已引导设备不受影响)。
# refMD Gesture_Widget_Overlay §14: com.android.provision 是临时 HOME(priority 1000),
# DEVICE_PROVISIONED=1 后不再作为 HOME, 不会出现引导向导。
# 用 settings 命令(service 阶段可用), 比直接改 settings_*.xml 安全。

PROVISIONED=$(settings get global device_provisioned 2>/dev/null)
if [ "$PROVISIONED" != "1" ]; then
    settings put global device_provisioned 1
    settings put secure user_setup_complete 1
    # 状态记录(可选排查): 出现 "flip_prop: provision skipped" 说明本次标记了跳过引导
    log -t flip_prop "provision skipped (device_provisioned was '$PROVISIONED')"
fi
