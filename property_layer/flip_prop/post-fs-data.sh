#!/system/bin/sh
# FlipUnlock 属性层 (flip1/flip2 通用, 2026-08-14 加机型判断)
# 核心: flip(4)->普通手机(1), 静态常量全变 (MiuiMultiDisplayTypeInfo.sDeviceType 等类加载时读到)
resetprop persist.sys.multi_display_type 1

# CWB 保护 (高通 display): 属性 1 触发 composer CWB 崩溃
# ValidateCwbConfigInfo null deref -> ConfigureCwbForIdleFallback -> 黑屏/热重启
# (refMD CWB_Inject_Breakage.md: 崩溃点 ldr w21,[x1,#0x24] = LayerBufferFormat 引用 null)
# ⚠️ 2026-08-14: 仅 flip1(2405CPX3DC)需要——flip2(2505APX7BC)固件不同, 未实测崩溃,
#    且 flip2 属性1 下 CWB 保护可能不必要(用户实测锁屏/渲染异常与此无关)。
#    按机型启用, 一个模块通吃两台, 不再需要 flip2_prop。
# disable_cwb_call=1 开机生效; enable_allow_idle_fallback 可能被 HAL 覆盖回 1, 前者足够
case "$(getprop ro.product.model)" in
  2405*)  # Xiaomi MIX Flip (flip1/ruyi)
    resetprop -n vendor.display.disable_cwb_call 1
    resetprop -n vendor.display.enable_allow_idle_fallback 0
    ;;
esac

# 部署: 目录复制到 /data/adb/modules/ 后重启 (建议目录名 flip_prop, 两台通用);
#       卸载: 删目录重启; flip2 上先删旧的 flip2_prop 再装本模块
#
# ⚠️ 开关无效说明 (HANDOFF #21, 2026-08-16):
#   persist.sys.multi_display_type 已被本脚本持久化到 /data/property/persistent_properties,
#   KSU/Magisk 里 disable 模块只是跳过脚本执行, persist 残留 → 重启后属性仍=1。
#   要恢复 flip 原生(属性 4), 任选其一:
#   a) 手动恢复命令(最快, 持久化写一次即可, 无需卸载模块):
#        su -c 'resetprop persist.sys.multi_display_type 4'
#   b) 卸载模块(会执行 uninstall.sh, 自动写回 4)
