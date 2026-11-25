#!/system/bin/sh
# flip_prop / service.sh  (companion module for the FlipOuterUnlock LSPosed module)
#
#   1. skip the setup wizard if the device was just factory-reset
#   2. if the LSPosed module is not installed -> restore identity 4 (native flip),
#      otherwise property 1 alone makes SystemUI crash-loop
#   3. crash self-heal: 3 SystemUI crashes within ~1 min -> restore identity 4
#   4. detect single-outer-screen mode (inner panel removed) -> persist.flipunlock.single_outer
#   4b. keep the /data display layout table in sync with the live display addresses
#   5. only in that mode: force device_state=CLOSED so the outer panel becomes
#      logical display 0, and pin the rotation
#   6. hide the two lock-screen shortcut buttons (torch / camera) via the MIUI
#      secure settings keyguard_shortcut_left/right = '{}'
#
# NOTE: the outer-screen app launch restriction ("open it on the inner screen") is
# NOT handled here on purpose. It disappears once identity is 1 and the LSPosed
# module is active, because the framework no longer treats the device as a flip.
#
# Switches:
#   persist.flipunlock.single_outer        (written here; 1 = inner panel removed)
#   persist.flipunlock.outer.force_closed  (default 1; 0 = skip, the layout table still keeps the outer as the main display)
#   persist.flipunlock.outer.pin_rotation  (default 1; 0 = don't touch rotation settings)
#   persist.flipunlock.provision_skip      (default 1; 0 = leave the setup wizard alone)
#   persist.flipunlock.ui.lockscreen_shortcuts (default 1; 0 = keep torch/camera buttons)
MODDIR=${0%/*}
LOG=/data/local/tmp/flip_prop_service.log
# 简单轮转（设备上没有 logrotate）：超过 ~256 KB 就切一份 .1，避免这个文件无限长大。
if [ -f "$LOG" ]; then
    sz=$(stat -c %s "$LOG" 2>/dev/null || echo 0)
    [ "$sz" -gt 262144 ] && mv -f "$LOG" "$LOG.1"
fi
exec >> $LOG 2>&1
echo "=== $(date) flip_prop service start ==="

# ---- 1. provision skip -------------------------------------------------------
# 开关: persist.flipunlock.provision_skip (默认 1)。设 0 = 完全不动 settings，
# 恢复出厂后自己走完向导（想保留"新机开箱"流程时用）。
if [ "$(getprop persist.flipunlock.provision_skip 1)" = "1" ]; then
    PROVISIONED=$(settings get global device_provisioned 2>/dev/null)
    if [ "$PROVISIONED" != "1" ]; then
        settings put global device_provisioned 1
        settings put secure user_setup_complete 1
        echo "provision skipped (was '$PROVISIONED')"
        log -t flip_prop "provision skipped"
    fi
else
    echo "provision skip disabled by persist.flipunlock.provision_skip=0"
fi

# ---- 2. LSP guard ------------------------------------------------------------
if ! ls /data/app/*/com.example.flipunlock-*/base.apk >/dev/null 2>&1; then
    setprop persist.sys.multi_display_type 4
    echo "LSPosed module not installed -> identity restored to 4"
    log -t flip_prop "LSP module missing, identity -> 4"
fi

# ---- 3. SystemUI crash self-heal --------------------------------------------
(
    i=0
    while [ "$i" -lt 30 ]; do
        [ "$(getprop persist.sys.multi_display_type)" = "1" ] || exit 0
        N=$(logcat -d -b crash 2>/dev/null | grep -c "Process: com.android.systemui")
        if [ "$N" -ge 3 ]; then
            setprop persist.sys.multi_display_type 4
            log -t flip_prop "SystemUI crash loop x$N -> identity 4"
            echo "self-heal: SystemUI crash x$N -> identity 4"
            killall com.android.systemui 2>/dev/null
            break
        fi
        i=$((i + 1))
        sleep 2
    done
) &

# ---- 4./5. single outer screen ----------------------------------------------
if [ "$(getprop ro.product.device)" = "bixi" ]; then
    i=0
    while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 150 ]; do
        i=$((i + 1))
        sleep 2
    done
    echo "boot_completed after $((i * 2))s"

    # The inner panel's touch controller (synaptics) registers its own input device.
    # Panel removed -> that device never appears. DRM connector status is useless
    # here (the DT still reports "connected"), so key off the input device name.
    if grep -qi "synaptics" /sys/class/input/event*/device/name 2>/dev/null; then
        SINGLE_OUTER=0
        echo "inner touch: present (synaptics input device found)"
    else
        SINGLE_OUTER=1
        echo "inner touch: absent (no synaptics input device) -> single-outer mode"
    fi
    setprop persist.flipunlock.single_outer $SINGLE_OUTER
    echo "persist.flipunlock.single_outer=$(getprop persist.flipunlock.single_outer)"

    # ---- 4b. display layout self-check (address drift after an OTA) ----------
    # post-fs-data installed the template using the measured OS4.0.3 addresses.
    # If the live addresses differ (ROM update, different phantom), rewrite the
    # table from what is actually present. The file is read at boot, so the new
    # table takes effect on the NEXT reboot -- say so in the log.
    if [ "$SINGLE_OUTER" = "1" ] && [ "$(getprop persist.flipunlock.outer.disable_inner 1)" = "1" ]; then
        F=/data/system/displayconfig/display_layout_configuration.xml
        OUTER=$(dumpsys display 2>/dev/null | grep -m1 -A1 'mDisplayId=0$' | sed -n 's/.*local:\([0-9]*\).*/\1/p' | head -1)
        INNER=$(dumpsys display 2>/dev/null | grep 'DisplayDeviceInfo' | grep -o 'local:[0-9]*' | sed 's/local://' | grep -v "^$OUTER$" | head -1)
        echo "display: live outer=$OUTER inner=$INNER"
        if [ -z "$OUTER" ] || [ -z "$INNER" ]; then
            echo "display: addresses unresolved -> layout untouched"
        elif grep -q "<address>$OUTER</address>" "$F" 2>/dev/null &&
             grep -q "<address>$INNER</address>" "$F" 2>/dev/null; then
            echo "display: layout table matches live displays"
        else
            mkdir -p /data/system/displayconfig
            {
                echo '<layouts>'
                for st in 0 1 2 3 4 5 6; do
                    echo '  <layout>'
                    echo "    <state>$st</state>"
                    echo '    <display enabled="true" defaultDisplay="true">'
                    echo "      <address>$OUTER</address>"
                    echo '    </display>'
                    echo '    <display enabled="false">'
                    echo "      <address>$INNER</address>"
                    echo '    </display>'
                    echo '  </layout>'
                done
                echo '</layouts>'
            } > "$F"
            chmod 644 "$F"
            restorecon -R /data/system/displayconfig 2>/dev/null
            echo "display: layout rewritten for live addresses -> takes effect next boot"
            log -t flip_prop "display layout rewritten (addresses drifted)"
        fi
    fi

# ---- 6. 锁屏底部两个快捷按钮（手电筒 / 相机）-------------------------------
# 外屏高度只有 1392px，这两个图标占掉底栏、还挡下滑手势，默认隐藏。
# 机制：MIUI 的锁屏快捷方式就是两个 secure 设置（left=手电筒 / right=相机），
#   把它们置成空对象 '{}' 后，下一次锁屏重建（重启 / 息屏再亮 / 重启 SystemUI）就不画了。
#   实测 2026-11-25：置 {} 后息屏再亮 → 两个图标消失；没有改任何分区、没有 hook。
# 开关: persist.flipunlock.ui.lockscreen_shortcuts（默认 1 = 隐藏；0 = 保留原样）
# 恢复: uninstall.sh 会写回原厂 JSON（左 FLASHLIGHT_SET / 右 CAMERA_CAPTURE）。
if [ "$(getprop persist.flipunlock.ui.lockscreen_shortcuts 1)" = "1" ]; then
    L=$(settings get secure keyguard_shortcut_left 2>/dev/null)
    R=$(settings get secure keyguard_shortcut_right 2>/dev/null)
    if [ "$L" != "{}" ] || [ "$R" != "{}" ]; then
        settings put secure keyguard_shortcut_left '{}'
        settings put secure keyguard_shortcut_right '{}'
        echo "lockscreen shortcuts hidden (was left=${L%%,*} right=${R%%,*})"
        log -t flip_prop "lockscreen shortcuts hidden"
    else
        echo "lockscreen shortcuts already hidden"
    fi
else
    echo "lockscreen shortcuts kept (persist.flipunlock.ui.lockscreen_shortcuts=0)"
fi

    if [ "$SINGLE_OUTER" = "1" ] && [ "$(getprop persist.flipunlock.outer.force_closed 1)" = "1" ]; then
        # 2026-11-24: 原来是"死等 uptime ≥ 60 再切"。那是布局表还没修好时（state 2 会指到内屏、
        # 首帧黑屏）的保险。现在 outer_only 布局表把每个 state 都映射到外屏，拓扑不再依赖时序
        # （实测：停在 HALF_OPENED 也能正常出图、旋转正常），所以改成"尽快切 + 失败重试"，
        # 把生效窗口从 60s 压到个位数秒。
        i=0
        while [ "$i" -lt 20 ]; do
            i=$((i + 1))
            if cmd device_state state 0 >/dev/null 2>&1; then
                echo "switch CLOSED at uptime=$(cut -d. -f1 /proc/uptime) try=$i"
                break
            fi
            echo "switch CLOSED retry $i at uptime=$(cut -d. -f1 /proc/uptime)"
            sleep 2
        done
        sleep 3
        echo "state_after=$(cmd device_state state 2>&1 | head -1)"

        # property 1: outer panel is a presentation display -> WMS gives ROTATION_90
        # 开关: persist.flipunlock.outer.pin_rotation (默认 1)。设 0 = 不钉旋转
        # （uninstall.sh 会把 accelerometer_rotation/user_rotation 与 wm 设置放回去）。
        if [ "$(getprop persist.sys.multi_display_type)" = "1" ] &&
           [ "$(getprop persist.flipunlock.outer.pin_rotation 1)" = "1" ]; then
            settings put system accelerometer_rotation 0
            settings put system user_rotation 0
            wm user-rotation lock 0
            wm fixed-to-user-rotation enabled
            echo "rotation pinned: $(dumpsys window 2>/dev/null | grep -m1 'mRotation=')"
        elif [ "$(getprop persist.flipunlock.outer.pin_rotation 1)" != "1" ]; then
            echo "rotation pin disabled by persist.flipunlock.outer.pin_rotation=0"
        fi

        # 2026-11-25: 外屏 AOD —— 内核把 layer_has_aod 只发给 primary（内屏，已拆），
        # 外屏进 LP1 时拿不到"有 AOD 图层"，自刷新显示的是进 doze 前那一帧（黑）→ 屏上没 AOD，
        # 但投屏/截图能截到。这里给外屏面板补写该标志：
        #   /sys/class/mi_display/disp-DSI-N/disp_param  feature id 39 = layer_has_aod，格式 "id value"
        # 判据：dmesg 出现 `[secondary] feature: layer_has_aod, value: 1`；随后 LP1 仍能显示 AOD。
        # 开关: persist.flipunlock.aod.panel_flag (默认 1)。sysfs 不持久，所以每次开机补一次。
        if [ "$(getprop persist.flipunlock.aod.panel_flag 1)" = "1" ]; then
            for n in /sys/class/mi_display/disp-DSI-*; do
                [ -d "$n" ] || continue
                info="$(cat "$n/panel_info" 2>/dev/null)"
                case "$info" in
                    *sec*)
                        echo "39 1" > "$n/disp_param" 2>/dev/null
                        echo "aod: layer_has_aod=1 -> $n ($info) rc=$?"
                        ;;
                esac
            done
        else
            echo "aod: layer_has_aod patch disabled (persist.flipunlock.aod.panel_flag=0)"
        fi
        echo "display0=$(dumpsys display 2>/dev/null | grep -A1 'mDisplayId=0$' | tr '\n' ' ')"
        echo "oops=$(dmesg | grep -c 'Internal error')"
    fi
fi

echo "=== flip_prop service done ==="
