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
