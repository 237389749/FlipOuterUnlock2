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

# ── SystemUI 崩溃环热自愈(2026-08-16, 方案B) ─────────────────────────
# 场景: 单独装 KSU 属性模块(属性1=伪装手机)而无 LSP 模块时, SystemUI 的
#   TinyKeyguardPanelViewController 构造 NPE → 崩溃环(refMD FoldState §38:
#   isFlipDevice→false 禁止作用于 SystemUI, 需 LSP DeviceIdentityHook 身份排除)。
# 自愈: 检测到崩溃环 → 属性还原 4(flip 原生) → killall SystemUI 让 AM 重启,
#   新进程读到属性 4 → isFlipDevice=true → 不再崩, 无需整机重启。
# 检测: 每 2s 抓一次 logcat crash buffer, 统计 **任何 SystemUI 崩溃**(进程行
#   "Process: com.android.systemui"), 累计 >= 3 次触发; 上限 30 次(约 1 分钟)。
#   不限定具体异常类(用户决定: SystemUI 反复崩溃即回退, 属性 4 是安全态)。
# 形态: 后台子 shell 不阻塞 boot; 仅属性=1 时生效(属性 4 直接跳过)。
# ⚠️ 局限: 模块被删/禁用时脚本不跑, 救不了"删模块+属性残留1"场景(靠一键联动卸载命令)。
(
    i=0
    while [ "$i" -lt 30 ]; do
        [ "$(getprop persist.sys.multi_display_type)" = "1" ] || exit 0
        N=$(logcat -d -b crash 2>/dev/null | grep -c "Process: com.android.systemui")
        if [ "$N" -ge 3 ]; then
            setprop persist.sys.multi_display_type 4
            log -t flip_prop "SystemUI 崩溃环自愈: SystemUI crash x$N → 属性还原 4"
            killall com.android.systemui 2>/dev/null
            break
        fi
        i=$((i + 1))
        sleep 2
    done
) &
