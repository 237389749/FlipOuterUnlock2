#!/system/bin/sh
# flip_prop / service.sh  (companion module for the FlipOuterUnlock LSPosed module)
#
#   1. skip the setup wizard if the device was just factory-reset
#   2. if the LSPosed module is not installed -> restore identity 4 (native flip)
#   3. crash self-heal: 3 SystemUI crashes within ~1 min -> restore identity 4
#   4. wait for boot_completed
#   5. clear the outer-screen app launch restriction (allowstart for every package)
#   6. detect single-outer-screen mode -> persist.flipunlock.single_outer
#   7. only in that mode: force device_state=CLOSED + pin the rotation
#
# Switches:
#   persist.flipunlock.single_outer        (written in step 6; 1 = inner panel removed)
#   persist.flipunlock.outer.force_closed  (default 1; 0 = let DisplayStateHook do it)
MODDIR=${0%/*}
LOG=/data/local/tmp/flip_prop_service.log
exec >> $LOG 2>&1
echo "=== $(date) flip_prop service start ==="

# ---- 1. provision skip -------------------------------------------------------
PROVISIONED=$(settings get global device_provisioned 2>/dev/null)
if [ "$PROVISIONED" != "1" ]; then
    settings put global device_provisioned 1
    settings put secure user_setup_complete 1
    echo "provision skipped (was '$PROVISIONED')"
    log -t flip_prop "provision skipped"
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

# ---- 4. wait for a booted system --------------------------------------------
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 150 ]; do
    i=$((i + 1))
    sleep 2
done
echo "boot_completed after $((i * 2))s"

# ---- 5. clear outer-screen app launch restriction ----------------------------
# The allowstart list lives in system_server memory (ContinuityPolicyService
# LOCAL_POLICY_BY_COMMAND); it short-circuits
# InterceptActivityController.isInterceptListUnCheckFold() at the highest
# priority, so "open it on the inner screen" never triggers. Re-apply every boot.
PKGS=$(pm list packages 2>/dev/null | sed 's/^package://' | tr '\n' ':' | sed 's/:$//')
if [ -n "$PKGS" ]; then
    OUT=$(dumpsys window -setForceDisplayCompatMode "$PKGS" allowstart 2>&1)
    echo "allowstart applied=$(echo "$OUT" | grep -c 'applied policy:allowstart') pkgs=$(echo "$PKGS" | tr ':' '\n' | wc -l)"
else
    echo "allowstart: no packages?!"
fi

# ---- 6./7. single outer screen ----------------------------------------------
if [ "$(getprop ro.product.device)" = "bixi" ]; then
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

    if [ "$SINGLE_OUTER" = "1" ] && [ "$(getprop persist.flipunlock.outer.force_closed 1)" = "1" ]; then
        while :; do
            up=$(cut -d. -f1 /proc/uptime)
            [ "$up" -ge 60 ] && break
            sleep 2
        done
        echo "switch CLOSED at uptime=$(cut -d. -f1 /proc/uptime) state_now=$(cmd device_state state 2>&1 | head -1)"
        cmd device_state state 0
        sleep 3
        echo "state_after=$(cmd device_state state 2>&1 | head -1)"

        # property 1: outer panel is a presentation display -> WMS gives ROTATION_90
        if [ "$(getprop persist.sys.multi_display_type)" = "1" ]; then
            settings put system accelerometer_rotation 0
            settings put system user_rotation 0
            wm user-rotation lock 0
            wm fixed-to-user-rotation enabled
            echo "rotation pinned: $(dumpsys window 2>/dev/null | grep -m1 'mRotation=')"
        fi
        echo "display0=$(dumpsys display 2>/dev/null | grep -A1 'mDisplayId=0$' | tr '\n' ' ')"
        echo "oops=$(dmesg | grep -c 'Internal error')"
    fi
fi

echo "=== flip_prop service done ==="
