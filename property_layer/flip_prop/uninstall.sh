#!/system/bin/sh
# flip_prop / uninstall.sh
#
# persist.sys.multi_display_type is written with `resetprop` (persisted to
# /data/property/persistent_properties), so removing/disabling the module does
# NOT restore it by itself - we must write the native flip value back.
#
# The flip2 touch patch needs no restore: the patched .ko only lives inside the
# module directory, so after removal the stock synaptics_tcm2.ko loads normally
# on the next boot.
resetprop persist.sys.multi_display_type 4

# flip1 CWB guard runtime props (harmless on flip2)
resetprop -n vendor.display.disable_cwb_call 0
resetprop -n vendor.display.enable_allow_idle_fallback 1

# drop the single-outer-screen switches so a re-install starts from defaults
resetprop -n persist.flipunlock.outer.force_closed 1

log -t flip_prop "uninstall: identity restored to 4 (flip native); touch patch gone after reboot"
