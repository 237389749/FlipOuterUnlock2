#!/system/bin/sh
# flip_prop / post-fs-data  (companion module for the FlipOuterUnlock LSPosed module)
#
# What it does, in order:
#   1. panic_on_oops=0            safety net, so a kernel oops does not hard-reset
#   2. multi_display_type 4 -> 1  flip identity -> "normal phone" (the LSPosed module
#                                 depends on it; without the LSP module service.sh
#                                 reverts this)
#   3. flip1 CWB guard            property 1 on flip1 trips a composer CWB crash
#   4. flip2 touch patch          inner panel removed -> goodix_ts_resume calls
#                                 synaptics get_capfold_information -> NULL -> oops.
#                                 We swap in a synaptics_tcm2.ko whose
#                                 get_capfold_information returns -ENODEV at entry.
#
# The touch patch is build-locked: EXPECTED_SHA must match the stock module of the
# running ROM. After an OTA the hash changes and the swap is skipped on purpose
# (touch keeps working with the stock driver); re-run out/tools/repatch_touch.py.
MODDIR=${0%/*}
LOG=/data/local/tmp/flip_prop_postfs.log
exec >> $LOG 2>&1
echo "=== $(date) flip_prop post-fs-data ==="

# ---- 1. safety net -----------------------------------------------------------
echo 0 > /proc/sys/kernel/panic_on_oops 2>/dev/null
echo "panic_on_oops=$(cat /proc/sys/kernel/panic_on_oops)"

# ---- 2. identity -------------------------------------------------------------
resetprop persist.sys.multi_display_type 1
echo "multi_display_type=$(getprop persist.sys.multi_display_type) device=$(getprop ro.product.device)"

# ---- 3. flip1 CWB guard ------------------------------------------------------
case "$(getprop ro.product.model)" in
  2405*)
    resetprop -n vendor.display.disable_cwb_call 1
    resetprop -n vendor.display.enable_allow_idle_fallback 0
    echo "flip1: CWB guard applied"
    ;;
esac

# ---- 4. flip2 touch patch ----------------------------------------------------
if [ "$(getprop ro.product.device)" = "bixi" ]; then
    STOCK=/vendor_dlkm/lib/modules/synaptics_tcm2.ko
    EXPECTED_SHA=04ae4874ea95a548d7a589304bc30f22f30d17cbd017e9fe7d32763f6aac7cfe
    KO=$MODDIR/synaptics_tcm2_patched.ko

    if [ ! -f "$KO" ]; then
        echo "touch patch: $KO missing, skip"
    else
        SHA=$(sha256sum "$STOCK" 2>/dev/null | cut -d' ' -f1)
        echo "touch patch: stock sha256=$SHA"
        if [ "$SHA" != "$EXPECTED_SHA" ]; then
            echo "touch patch: !! stock module changed (OTA?) -> skip, touch stays stock"
            log -t flip_prop "synaptics module changed, touch patch SKIPPED"
        else
            echo "touch patch: before goodix=$(cat /sys/module/goodix_core/refcnt 2>/dev/null) synaptics=$(cat /sys/module/synaptics_tcm2/refcnt 2>/dev/null)"
            timeout 10 rmmod goodix_core
            echo "touch patch: rmmod goodix rc=$?"
            timeout 10 rmmod synaptics_tcm2
            echo "touch patch: rmmod synaptics rc=$?"
            sleep 1
            timeout 10 insmod "$KO"
            echo "touch patch: insmod patched rc=$?"
            sleep 1
            timeout 10 insmod /vendor_dlkm/lib/modules/goodix_core.ko
            echo "touch patch: insmod goodix rc=$?"
        fi
    fi
fi

echo "=== flip_prop post-fs-data done ==="
