#!/system/bin/sh
# flip_prop / uninstall.sh
#
# persist.sys.multi_display_type is written with `resetprop` (persisted to
# /data/property/persistent_properties), so removing/disabling the module does
# NOT restore it by itself - we must write the native flip value back.
#
# The flip2 touch patch needs no restore: the patched .ko only lives inside the
# module directory, so after removal the stock synaptics_tcm2.ko loads normally
# on the next boot. Same for the rotation settings written by service.sh.
#
# 2026-11-23 (code review 🟡): the previous version only reset three props and
# left `accelerometer_rotation` / `user_rotation` pinned forever, and it wrote
# `outer.force_closed=1` (which service.sh reads as "ON", the opposite of what
# the comment promised). Everything this module persists is now reverted.
resetprop persist.sys.multi_display_type 4
echo "uninstall: multi_display_type=$(getprop persist.sys.multi_display_type)"

# flip1 CWB guard runtime props (harmless on flip2)
resetprop -n vendor.display.disable_cwb_call 0
resetprop -n vendor.display.enable_allow_idle_fallback 1

# drop the single-outer-screen switches instead of "setting them to the default":
# a leftover single_outer=1 keeps unlock2 in single-outer mode (rotation lock,
# forced CLOSED) after the user thought everything was removed.
resetprop --delete persist.flipunlock.single_outer 2>/dev/null
resetprop --delete persist.flipunlock.outer.force_closed 2>/dev/null
resetprop --delete persist.flipunlock.outer.disable_inner 2>/dev/null
resetprop --delete persist.flipunlock.touchpatch.status 2>/dev/null

# display layout override: without this the phantom second display stays
# disabled across the uninstall (the file lives in /data, not in the module).
# Only our own file is removed; MIUI's display_cloud_backup.xml is left alone.
rm -f /data/system/displayconfig/display_layout_configuration.xml
echo "uninstall: display layout override removed (phantom display returns next boot)"

# rotation pinned by service.sh -> give auto-rotate back to the user
settings delete system accelerometer_rotation 2>/dev/null
settings delete system user_rotation 2>/dev/null
wm user-rotation free 2>/dev/null
wm fixed-to-user-rotation disabled 2>/dev/null
echo "uninstall: rotation settings released"

log -t flip_prop "uninstall: identity 4, switches dropped, rotation released; touch patch gone after reboot"
