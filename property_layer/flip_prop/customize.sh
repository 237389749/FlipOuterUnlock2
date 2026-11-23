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

# ── 2026-11-23: 保护已有的 patched ko ────────────────────────────────────────
# CI 出的 zip 里没有 payload（.ko 是 ROM 相关产物，不进仓库），而 KernelSU 安装时
# 会用新目录替换旧模块。直接覆盖安装会把设备上那份能用的 ko 冲掉 -> 触摸补丁静默跳过
# -> 息屏 63s 硬复位复活。这里在安装阶段把旧模块里的 payload 继承过来。
OLD=/data/adb/modules/flipunlock_prop
for f in synaptics_tcm2_patched.ko; do
    if [ ! -f "$MODPATH/$f" ] && [ -f "$OLD/$f" ]; then
        cp -f "$OLD/$f" "$MODPATH/$f"
        ui_print "  [FlipUnlock] carried over existing $f from the installed module"
    fi
done

if [ "$DEV" = "bixi" ] && [ ! -f "$MODPATH/synaptics_tcm2_patched.ko" ]; then
    ui_print "  [FlipUnlock] NOTE: synaptics_tcm2_patched.ko missing (zip has no payload,"
    ui_print "            and none could be carried over)"
    ui_print "            -> inner-screen-removed hard reset is NOT fixed"
    ui_print "            -> push a patched ko into $MODPATH before rebooting"
fi

ui_print "  [FlipUnlock] install ok"
