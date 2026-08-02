package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Force dual-screen state so both displays stay active with outer as primary.
 *
 * Logic chain (refMD: Hook_Chain_Map.md §S1):
 *
 *   Physical fold sensor → DeviceStateManager callback
 *     → LogicalDisplayMapper.setDeviceStateLocked(state)
 *       → applyLayoutLocked()
 *         → DeviceStateToLayoutMap.get(state)  ← ★ CHOKE POINT
 *           → enables/disables displays per layout XML
 *
 *   State 6 (DUAL) in display_layout_configuration.xml:
 *     port=132 (outer) = displayId=0 (primary)
 *     port=131 (inner) follows
 *
 * Three hooks form the minimum upstream set:
 *   §1  setDeviceStateLocked → state=0 (CLOSED)
 *       Display layer sees "folded" → outer screen stays active.
 *
 *   §1b DeviceStateToLayoutMap.get() → layout for state=6 (DUAL)
 *       Both screens enabled, outer leads as displayId=0.
 *
 *   §3  getDisplayInfoForStateLocked → state=0
 *       All display-info queries see CLOSED layout (SystemUI pre-compute).
 *
 * Defensive hooks (isEnabledLocked, disableExternalDisplayLocked) are NOT
 * included — add only if testing reveals display-disable leaks.
 *
 * Process: system_server
 * Toggle:  persist.flipunlock.display.dual (default: true)
 */
object DisplayState {

    /**
     * Are we running on the outer (cover) screen?
     * In system_server, Resources.getSystem() reflects the default display.
     * Outer: max(w,h) = 1392. Inner: max(w,h) = 2508. Threshold: 2000.
     */
    private fun isOuterScreen(): Boolean {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        return maxOf(dm.widthPixels, dm.heightPixels) < 2000
    }

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayDual) {
            log("DisplayState: DISABLED by config")
            return
        }
        log("DisplayState: setting up (3 core hooks)")
        safeHook("DisplayState") {
            hookSetDeviceState(param)
            hookLayoutMapGet(param)
            hookDisplayInfoForState(param)
        }
    }

    // ── §1. setDeviceStateLocked → always CLOSED (state=0) ─────────────
    private fun hookSetDeviceState(param: SystemServerStartingParam) {
        runCatching {
            val mapperClass = param.classLoader.loadClass(
                "com.android.server.display.LogicalDisplayMapper")
            val deviceStateClass = param.classLoader.loadClass(
                "android.hardware.devicestate.DeviceState")
            val method = mapperClass.method("setDeviceStateLocked", deviceStateClass)

            // DeviceState(int identifier) — public constructor
            val closedState = deviceStateClass
                .getDeclaredConstructor(Int::class.javaPrimitiveType!!)
                .newInstance(0)

            hook(method) { chain ->
                if (isOuterScreen()) {
                    chain.args[0] = closedState
                }
                chain.proceed()
            }
            log("DisplayState: §1 setDeviceStateLocked → CLOSED")
        }.onFailure { log("DisplayState: §1 failed", it) }
    }

    // ── §1b. DeviceStateToLayoutMap.get() → state=6 (DUAL) ★ ──────────
    private fun hookLayoutMapGet(param: SystemServerStartingParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.display.DeviceStateToLayoutMap")
            val method = cls.getDeclaredMethod("get", Int::class.javaPrimitiveType!!)
            method.isAccessible = true

            hook(method) { chain ->
                if (!isOuterScreen()) return@hook chain.proceed()
                val state = chain.args[0] as? Int ?: return@hook chain.proceed()
                val layoutMap = chain.thisObject.getField("mLayoutMap")
                val dualLayout = (layoutMap as android.util.SparseArray<*>).get(6)
                if (state != 6 && dualLayout != null) {
                    log("DisplayState: §1b get($state) → forcing state=6 (DUAL)")
                }
                dualLayout ?: chain.proceed()
            }
            log("DisplayState: §1b DeviceStateToLayoutMap.get → state=6")
        }.onFailure { log("DisplayState: §1b failed", it) }
    }

    // ── §3. getDisplayInfoForStateLocked → state=0 ─────────────────────
    private fun hookDisplayInfoForState(param: SystemServerStartingParam) {
        runCatching {
            val mapperClass = param.classLoader.loadClass(
                "com.android.server.display.LogicalDisplayMapper")
            val method = mapperClass.method(
                "getDisplayInfoForStateLocked",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!)

            hook(method) { chain ->
                if (isOuterScreen()) {
                    chain.args[0] = 0  // force deviceState=0 (CLOSED)
                }
                chain.proceed()
            }
            log("DisplayState: §3 getDisplayInfoForStateLocked → state=0")
        }.onFailure { log("DisplayState: §3 failed", it) }
    }
}
