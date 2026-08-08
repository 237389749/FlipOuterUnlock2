package com.example.flipunlock.hook.system_server

import android.util.SparseArray
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Display topology lock for Flip1 with the INNER screen physically removed.
 *
 * Ported from FlipOuterUnlock (legacy) DisplayStateHook, re-validated against
 * live dumpsys + FlipRes/services.jar on ruyi OS3.0.3.0.WNICNXM (HyperOS3).
 *
 * ── Topology on this firmware (refMD §39) ──────────────────────────
 *   displayId 0 = OUTER (port=132, 1208x1392, FLAG_PRESENTATION, default)
 *   displayId 1 = INNER (port=131, 1080x2340) — physically removed, but
 *                 still enumerated by the system.
 *   NOTE: legacy MIUI/HyperOS2 firmwares used outer=5/inner=0; the old
 *   project's state=6 (dual) strategy is obsolete here — state 6 would
 *   enable the dead inner display.
 *
 * ── State→layout map (dumpsys display, this device) ────────────────
 *   state 0/1/4 : disp0(outer) ON,  disp1(inner) OFF   ← we pin this
 *   state 2/3   : disp1(inner) ON,  disp0(outer) OFF   ← must never apply
 *   state 5/6   : both ON (lead=0/1)
 *
 * Strategy: pin the display layer to CLOSED (state 0) so the outer screen
 * stays the primary/enabled display, and block every path that could
 * disable it. With the inner panel gone, any transition to state 2/3
 * would switch the primary to a dead display → black device.
 *
 * Process: system_server. Flip1-only (DeviceGuard), gated by
 * persist.flipunlock.display.dual (Config.displayDual).
 */
object DisplayTopologyHook {

    /** CLOSED state identifier (outer ON, inner OFF on this firmware). */
    private const val STATE_CLOSED = 0
    /** Outer display id on Flip1 HyperOS3 (§39). */
    private const val OUTER_DISPLAY_ID = 0

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayDual) {
            log("DisplayTopology: DISABLED by persist.flipunlock.display.dual")
            return
        }
        if (!DeviceGuard.isFlip1) {
            log("DisplayTopology: skipped on gen=${DeviceGuard.gen} (Flip1-only, inner-removed scenario)")
            return
        }
        log("DisplayTopology: setting up (inner screen removed → pin outer as primary)")
        safeHook("DisplayTopology") {
            hookSetDeviceStateToClosed(param)      // #1 state transitions → CLOSED
            hookDisplayLayoutGetToClosed(param)    // #2 layout map lookup → state 0 layout
            hookDisplayInfoForStateToClosed(param) // #3 hypothetical queries → state 0
            hookOuterDisplayEnabledLocked(param)   // #4 outer display never "disabled"
            hookBlockDisableExternalDisplay(param) // #5 block ExternalDisplayPolicy kill
        }
    }

    // ── #1 LogicalDisplayMapper.setDeviceStateLocked(DeviceState) ──────
    // Replace any incoming state with the real CLOSED DeviceState instance.
    // We reuse the system's own instance (built via DeviceState.Configuration
    // Builder) so properties (wake/sleep triggers etc.) stay coherent.
    private fun hookSetDeviceStateToClosed(param: SystemServerStartingParam) {
        runCatching {
            val mapperClass = param.classLoader.loadClass(
                "com.android.server.display.LogicalDisplayMapper")
            val deviceStateClass = param.classLoader.loadClass(
                "android.hardware.devicestate.DeviceState")
            val method = mapperClass.method("setDeviceStateLocked", deviceStateClass)

            val closedState = buildClosedState(param.classLoader, deviceStateClass)
                ?: run {
                    log("DisplayTopology: cannot build DeviceState(0), #1 skipped")
                    return@runCatching
                }

            hook(method) { chain ->
                val incoming = chain.args[0]
                val id = incoming?.callMethod("getIdentifier") as? Int
                if (id != null && id != STATE_CLOSED) {
                    log("DisplayTopology: setDeviceStateLocked($id) → forced CLOSED(0)")
                    chain.args[0] = closedState
                }
                chain.proceed()
            }
            log("DisplayTopology: ✓ setDeviceStateLocked → always CLOSED")
        }.onFailure { log("DisplayTopology: #1 setDeviceStateLocked failed", it) }
    }

    /** DeviceState(Configuration(new Builder(0,"CLOSED").build())) via reflection. */
    private fun buildClosedState(loader: ClassLoader, deviceStateClass: Class<*>): Any? {
        return runCatching {
            val configClass = loader.loadClass(
                "android.hardware.devicestate.DeviceState\$Configuration")
            val builderClass = loader.loadClass(
                "android.hardware.devicestate.DeviceState\$Configuration\$Builder")
            val builder = builderClass
                .getDeclaredConstructor(Int::class.javaPrimitiveType, String::class.java)
                .newInstance(STATE_CLOSED, "CLOSED")
            val config = builderClass.getMethod("build").invoke(builder)
            deviceStateClass.getConstructor(configClass).newInstance(config)
        }.getOrNull()
    }

    // ── #2 DeviceStateToLayoutMap.get(int) → state 0 layout ────────────
    // The choke point of applyLayoutLocked(). Returning the CLOSED layout
    // keeps outer ON / inner OFF regardless of which state is applied.
    private fun hookDisplayLayoutGetToClosed(param: SystemServerStartingParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.display.DeviceStateToLayoutMap")
            val method = cls.getDeclaredMethod("get", Int::class.javaPrimitiveType!!)
            method.isAccessible = true

            hook(method) { chain ->
                val state = chain.args[0] as? Int
                if (state != null && state != STATE_CLOSED) {
                    val layoutMap = chain.thisObject.getField("mLayoutMap") as? SparseArray<*>
                    val closedLayout = layoutMap?.get(STATE_CLOSED)
                    if (closedLayout != null) {
                        log("DisplayTopology: layout get($state) → state 0 (outer ON)")
                        return@hook closedLayout
                    }
                }
                chain.proceed()
            }
            log("DisplayTopology: ✓ DeviceStateToLayoutMap.get → CLOSED layout")
        }.onFailure { log("DisplayTopology: #2 layout get failed", it) }
    }

    // ── #3 getDisplayInfoForStateLocked(deviceState, displayId) ────────
    // SystemUI/others pre-compute DisplayInfo for hypothetical states.
    // Force state=0 so every query resolves to the outer-led composition.
    private fun hookDisplayInfoForStateToClosed(param: SystemServerStartingParam) {
        runCatching {
            val mapperClass = param.classLoader.loadClass(
                "com.android.server.display.LogicalDisplayMapper")
            val method = mapperClass.method(
                "getDisplayInfoForStateLocked",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!)
            hook(method, before { chain ->
                val state = chain.args[0] as? Int
                if (state != null && state != STATE_CLOSED) {
                    chain.args[0] = STATE_CLOSED
                }
            })
            log("DisplayTopology: ✓ getDisplayInfoForStateLocked → state 0")
        }.onFailure { log("DisplayTopology: #3 getDisplayInfoForStateLocked failed", it) }
    }

    // ── #4 LogicalDisplay.isEnabledLocked() → true for the outer only ──
    // Scoped to the outer displayId: forcing the dead inner display
    // "enabled" could trigger attach attempts to the removed panel.
    private fun hookOuterDisplayEnabledLocked(param: SystemServerStartingParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.display.LogicalDisplay")
            val method = cls.getDeclaredMethod("isEnabledLocked")
            method.isAccessible = true
            hook(method) { chain ->
                val displayId = chain.thisObject.callMethod("getDisplayIdLocked") as? Int
                if (displayId == OUTER_DISPLAY_ID) true else chain.proceed()
            }
            log("DisplayTopology: ✓ isEnabledLocked → true for outer (display $OUTER_DISPLAY_ID)")
        }.onFailure { log("DisplayTopology: #4 isEnabledLocked failed", it) }
    }

    // ── #5 ExternalDisplayPolicy.disableExternalDisplayLocked ───────────
    // Outer carries FLAG_PRESENTATION and can be classified "external";
    // block the policy from ever disabling it. Signature on HyperOS3:
    // disableExternalDisplayLocked(LogicalDisplay) — legacy firmware took int.
    private fun hookBlockDisableExternalDisplay(param: SystemServerStartingParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.server.display.ExternalDisplayPolicy")
            val logicalDisplayClass = param.classLoader.loadClass(
                "com.android.server.display.LogicalDisplay")
            val method = cls.getDeclaredMethod(
                "disableExternalDisplayLocked", logicalDisplayClass)
            method.isAccessible = true
            hook(method) { chain ->
                val displayId = chain.args[0]?.callMethod("getDisplayIdLocked") as? Int
                log("DisplayTopology: BLOCKED disableExternalDisplayLocked(display=$displayId)")
                null
            }
            log("DisplayTopology: ✓ disableExternalDisplayLocked blocked")
        }.onFailure { log("DisplayTopology: #5 disableExternalDisplayLocked failed", it) }
    }
}
