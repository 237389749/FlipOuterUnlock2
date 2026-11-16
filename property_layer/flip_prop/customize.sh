#!/system/bin/sh
# flip_prop / customize.sh  (install-time checks)
#
# Property 1 alone (no LSPosed module) makes SystemUI crash-loop on flip devices,
# so refuse to install without the companion APK. Same guard as before, plus a
# device check and a note when the flip2 touch-patch payload is missing.

if ! pm path com.example.flipunlock >/dev/null 2>&1; then
    ui_print ""
    ui_print "  [FlipUnlock] the LSPosed module com.example.flipunlock is NOT installed!"
    ui_print "  Installing this module alone (identity 1) makes SystemUI crash-loop."
    ui_print "  Install / enable the LSPosed module first."
    ui_print "  (force install: copy the folder into /data/adb/modules and reboot)"
    ui_print ""
    abort "Aborting: LSPosed module com.example.flipunlock not installed"
fi
ui_print "  [FlipUnlock] ok: LSPosed module present"

DEV=$(getprop ro.product.device)
ui_print "  [FlipUnlock] device = $DEV"
case "$DEV" in
    bixi)  ui_print "  [FlipUnlock] flip2: touch patch (synaptics capfold) applies" ;;
    ruyi)  ui_print "  [FlipUnlock] flip1: CWB guard applies, no touch patch needed" ;;
    *)     ui_print "  [FlipUnlock] WARNING: unknown device, continuing anyway" ;;
esac

if [ "$DEV" = "bixi" ] && [ ! -f "$MODPATH/synaptics_tcm2_patched.ko" ]; then
    ui_print "  [FlipUnlock] NOTE: synaptics_tcm2_patched.ko missing in the zip"
    ui_print "            -> inner-screen-removed hard reset is NOT fixed"
fi

ui_print "  [FlipUnlock] install ok"
