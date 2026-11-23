#!/system/bin/sh
# flip_prop / post-fs-data  (companion module for the FlipOuterUnlock LSPosed module)
#
# What it does, in order:
#   1. multi_display_type 4 -> 1  flip identity -> "normal phone" (the LSPosed module
#                                 depends on it; without the LSP module service.sh
#                                 reverts this)
#   2. flip1 CWB guard            property 1 on flip1 trips a composer CWB crash
#   3. flip2 touch patch          inner panel removed -> goodix_ts_resume calls
#                                 synaptics get_capfold_information -> NULL -> oops.
#                                 We swap in a synaptics_tcm2.ko whose
#                                 get_capfold_information returns -ENODEV at entry.
#   4. safety net                 panic_on_oops=0 ONLY after the patched module is
#                                 proven loaded (see the note at step 4)
#
# 2026-11-23 hardening (code review 🔴1.3/1.4):
#   * EXPECTED_SHA is now a ROM table, not a single 4.0.3 hash -- an OTA used to skip
#     the patch silently and bring the 63s hard reset back.
#   * the swap is now "unload -> load -> verify" with ROLLBACK: any failure puts the
#     original driver back. Before this, a failed insmod left BOTH touch drivers
#     unloaded (no touch at all, no way to recover in post-fs-data).
#   * panic_on_oops stays at the system default unless the patch actually loaded:
#     with the patch skipped, an oops + reset is safer than a lit-but-dead screen.
MODDIR=${0%/*}
LOG=/data/local/tmp/flip_prop_postfs.log
exec > $LOG 2>&1          # overwrite, not append (the log used to grow forever)
echo "=== $(date) flip_prop post-fs-data ==="

# ---- 1. identity -------------------------------------------------------------
resetprop persist.sys.multi_display_type 1
echo "multi_display_type=$(getprop persist.sys.multi_display_type) device=$(getprop ro.product.device) rom=$(getprop ro.build.version.incremental)"

# ---- 1b. single outer screen: disable the phantom second display -------------
# Why: with the inner panel removed the DSI panel is not recognised
#   (dmesg: panel_recognize_err / mi,panel-id not specified / no port node in
#    qcom,dsi-display-primary), so the driver falls back to a default mode and
#   the framework ends up with a SECOND display (1440x3200 -- a slab-phone mode,
#   not this panel's 1224x2912).
# That phantom shares display group 0 with the outer screen, composes every
#   frame, and forces SurfaceFlinger's PassBlur to be rebuilt once per viewport;
#   the outer screen then alternates between its own crop and the phantom's
#   centred crop -> the control-centre / big-folder flicker
#   (refMD FoldState_Device_Identity.md 44.14 H/K).
# The only user-space fix is a display layout table that disables the phantom by
#   address; /data/system/displayconfig/ overrides /odm and survives OTA.
# Switches:
#   persist.flipunlock.outer.disable_inner (default 1; 0 = leave the phantom on)
#   persist.flipunlock.single_outer       (set by service.sh once the inner
#                                          panel's touch device is confirmed absent)
# Template: $MODDIR/outer_only_layout.xml (bixi / OS4.0.3 measured addresses).
#   service.sh rewrites it when the live addresses drift, effective next boot.
if [ "$(getprop ro.product.device)" = "bixi" ]; then
    if [ "$(getprop persist.flipunlock.outer.disable_inner 1)" != "1" ]; then
        echo "display: phantom disable off (persist.flipunlock.outer.disable_inner=0)"
    elif [ "$(getprop persist.flipunlock.single_outer)" != "1" ]; then
        echo "display: phantom disable skipped (single_outer not set yet -> next boot)"
    elif [ ! -f "$MODDIR/outer_only_layout.xml" ]; then
        echo "display: outer_only_layout.xml missing in module -> phantom stays on"
    else
        mkdir -p /data/system/displayconfig
        cp -f "$MODDIR/outer_only_layout.xml" /data/system/displayconfig/display_layout_configuration.xml
        chmod 644 /data/system/displayconfig/display_layout_configuration.xml
        restorecon -R /data/system/displayconfig 2>/dev/null
        echo "display: phantom second display disabled via /data layout table"
    fi
fi

# ---- 2. flip1 CWB guard ------------------------------------------------------
case "$(getprop ro.product.model)" in
  2405*)
    resetprop -n vendor.display.disable_cwb_call 1
    resetprop -n vendor.display.enable_allow_idle_fallback 0
    echo "flip1: CWB guard applied"
    ;;
esac

# ---- 3. flip2 touch patch (with rollback) ------------------------------------
PATCH_OK=0
if [ "$(getprop ro.product.device)" = "bixi" ]; then
    STOCK=/vendor_dlkm/lib/modules/synaptics_tcm2.ko
    GOODIX=/vendor_dlkm/lib/modules/goodix_core.ko
    KO=$MODDIR/synaptics_tcm2_patched.ko
    VER=$(getprop ro.build.version.incremental)

    # stock synaptics_tcm2.ko sha256 per ROM build. Unknown build -> do not guess.
    # 2026-11-23 measured: the 4.0.4 fastboot package ships a vendor_dlkm whose
    # synaptics_tcm2.ko is byte-identical to 4.0.3's, and the running kernel is the
    # same 6.6.118 build -- so ONE payload serves both. (7a1c0640... came from a
    # different build: it never matched the package and silently skipped the patch.)
    case "$VER" in
      OS4.0.3*|OS4.0.4*) EXPECTED_SHA=04ae4874ea95a548d7a589304bc30f22f30d17cbd017e9fe7d32763f6aac7cfe ;;
      *)                 EXPECTED_SHA="" ;;
    esac

    ins() { timeout 10 insmod "$1"; }

    # One owner for the driver swap: the touch-only module is installed before
    # LSPosed exists, so when it is present it owns the swap and we must not
    # rmmod/insmod a second time in the same boot (post-fs-data order is not fixed).
    TOUCH_OWNER=""
    OWNER=/data/adb/modules/flip_outer_force
    if [ -f "$OWNER/synaptics_tcm2_patched.ko" ] && [ ! -e "$OWNER/disable" ]; then
        TOUCH_OWNER=flip_outer_force
    fi

    if [ -n "$TOUCH_OWNER" ]; then
        echo "touch: delegated to $TOUCH_OWNER (owner present) -> no second swap"
    elif [ -z "$EXPECTED_SHA" ]; then
        echo "touch: unknown ROM '$VER' -> skip (stock driver untouched)"
    elif [ ! -f "$KO" ]; then
        echo "touch: $KO missing -> skip (stock driver untouched)"
    elif [ ! -d /sys/module/synaptics_tcm2 ]; then
        echo "touch: synaptics_tcm2 not loaded yet -> skip (leave it to the system)"
    else
        SHA=$(sha256sum "$STOCK" 2>/dev/null | cut -d' ' -f1)
        echo "touch: stock sha256=$SHA"
        if [ "$SHA" != "$EXPECTED_SHA" ]; then
            echo "touch: sha mismatch -> skip (stock stays; rebuild out/tools/repatch_touch.py)"
            log -t flip_prop "synaptics module changed, touch patch SKIPPED"
        else
            # remember the pre-state so we can restore exactly that
            GOODIX_WAS=0
            [ -d /sys/module/goodix_core ] && GOODIX_WAS=1
            echo "touch: before goodix_present=$GOODIX_WAS synaptics=$(cat /sys/module/synaptics_tcm2/refcnt 2>/dev/null)"

            timeout 10 rmmod goodix_core
            echo "touch: rmmod goodix rc=$? (136/ENOENT = was not loaded)"
            timeout 10 rmmod synaptics_tcm2
            S_RC=$?
            echo "touch: rmmod synaptics rc=$S_RC"

            if [ "$S_RC" != "0" ]; then
                # could not unload (busy) -> put back whatever we removed
                [ "$GOODIX_WAS" = "1" ] && ins "$GOODIX"
                echo "touch: abort (synaptics busy), goodix restored=$([ -d /sys/module/goodix_core ] && echo yes || echo no)"
            elif ins "$KO"; then
                echo "touch: patched synaptics loaded"
                if [ "$GOODIX_WAS" = "1" ]; then
                    ins "$GOODIX"
                    echo "touch: goodix reload rc=$? present=$([ -d /sys/module/goodix_core ] && echo yes || echo no)"
                fi
                [ -d /sys/module/goodix_core ] && PATCH_OK=1
                [ "$PATCH_OK" = "0" ] && log -t flip_prop "touch patch: goodix NOT reloaded, touch may be dead"
            else
                # patched module refused -> restore the stock one, then goodix
                echo "touch: patched insmod FAILED -> restoring stock driver"
                ins "$STOCK"
                echo "touch: stock restore rc=$? present=$([ -d /sys/module/synaptics_tcm2 ] && echo yes || echo no)"
                [ "$GOODIX_WAS" = "1" ] && ins "$GOODIX"
                echo "touch: goodix restore present=$([ -d /sys/module/goodix_core ] && echo yes || echo no)"
                log -t flip_prop "touch patch failed, stock driver restored"
            fi
        fi
    fi
fi
if [ -n "$TOUCH_OWNER" ]; then
    TOUCH_STATUS=delegated
elif [ "$PATCH_OK" = "1" ]; then
    TOUCH_STATUS=patched
else
    TOUCH_STATUS=stock
fi
setprop persist.flipunlock.touchpatch.status "$TOUCH_STATUS"
echo "touch: patch_ok=$PATCH_OK status=$(getprop persist.flipunlock.touchpatch.status)"

# ---- 4. safety net (only when the patch is really in) -------------------------
# Rationale: with panic_on_oops=0 and a SKIPPED patch, an oops leaves a lit screen
# with dead touch and no auto-reboot -- worse than the 63s reset it was meant to avoid.
if [ "$PATCH_OK" = "1" ]; then
    echo 0 > /proc/sys/kernel/panic_on_oops 2>/dev/null
    echo "safety net: panic_on_oops=0 (patched driver is live)"
else
    echo "safety net: panic_on_oops left at $(cat /proc/sys/kernel/panic_on_oops 2>/dev/null) (patch not applied)"
fi

echo "=== flip_prop post-fs-data done ==="
