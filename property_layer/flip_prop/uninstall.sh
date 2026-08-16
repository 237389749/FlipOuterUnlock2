#!/system/bin/sh
# FlipUnlock 属性层卸载恢复 (2026-08-16)
# ⚠️ 原因: persist.sys.multi_display_type 由 post-fs-data.sh 用 `resetprop`(无 -n)写入,
#   已持久化到 /data/property/persistent_properties —— KSU/Magisk 里 disable/删除模块只是
#   跳过脚本执行, persist 残留不会自动消失 → 重启后属性仍=1(开关无效, HANDOFF #21)。
#   卸载时必须在脚本里显式写回原生值 4(flip), 否则设备会保持"伪装手机"状态。
# 仅卸载模块时执行; 若只是临时禁用模块(开关), 请用下面手动恢复命令(见 post-fs-data.sh 头部)。

# 1) 核心: multi_display_type 写回原生 flip 值 4(无 -n, 重新持久化覆盖旧值)
resetprop persist.sys.multi_display_type 4

# 2) CWB 保护属性(flip1, post-fs-data.sh 设置的运行时值)恢复默认。
#    -n 只改运行时, 重启后本应由固件默认值覆盖; 这里显式恢复避免卸载后热重启踩 CWB 崩溃。
#    ⚠️ 卸载后无需再重启: persist 已写回 4, 下次重启即恢复 flip 原生行为。
resetprop -n vendor.display.disable_cwb_call 0
resetprop -n vendor.display.enable_allow_idle_fallback 1

log -t flip_prop "uninstall: multi_display_type restored to 4 (flip native)"
