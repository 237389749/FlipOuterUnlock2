package com.example.flipunlock.hook.util

import android.content.Context
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/**
 * Cross-generation device guard — runtime environment detection.
 *
 * Supported devices (refMD §34/§36):
 *   Flip 1 "ruyi"  (HyperOS2, multi_display_type=4)
 *     outer 1208x1392 = displayId 5 (SECONDARY display)
 *     inner 1555x2508 = displayId 0
 *   Flip 2 "bixi"  (HyperOS3, multi_display_type=4, muiltdisplay_type=16)
 *     outer 1208x1392 = displayId 0 (DEFAULT display!)
 *     inner 1224x2912 = displayId 1
 *
 * Detection is ro.product.device based — vendor codenames are stable
 * hardcodes; never infer the generation from display topology or
 * resolution combinations.
 *
 * Usage in hooks (single file = both generations, branched internally):
 *   when (DeviceGuard.gen) {
 *       DeviceGen.FLIP1 -> hookFlip1Targets(loader)
 *       DeviceGen.FLIP2 -> hookFlip2Targets(loader)
 *   }
 *
 * All probes are cached; SystemProperties is a hidden API accessed via
 * reflection (same approach as Config.kt).
 */
object DeviceGuard {

    enum class DeviceGen { FLIP1, FLIP2, UNKNOWN }

    // ── Vendor codenames ───────────────────────────────────────────
    private const val CODENAME_FLIP1 = "ruyi"
    private const val CODENAME_FLIP2 = "bixi"

    // ── System properties (identity chain roots) ──────────────────
    private const val PROP_MULTI_DISPLAY_TYPE = "persist.sys.multi_display_type"   // 4 on both gens
    private const val PROP_MUILTDISPLAY_TYPE = "persist.sys.muiltdisplay_type"     // Flip2 only (=16)

    /** Device generation. Cached; detection runs once per process. */
    val gen: DeviceGen by lazy {
        when (prop("ro.product.device")) {
            CODENAME_FLIP1 -> DeviceGen.FLIP1
            CODENAME_FLIP2 -> DeviceGen.FLIP2
            else -> DeviceGen.UNKNOWN
        }
    }

    val isFlip1: Boolean get() = gen == DeviceGen.FLIP1
    val isFlip2: Boolean get() = gen == DeviceGen.FLIP2

    // ── Display topology constants (folded, outer active) ─────────
    // CORRECTED 2026-08-08 by live dumpsys (ruyi, OS3.0.3.0.WNICNXM / HyperOS3):
    //   Flip1 HyperOS3: outer = displayId 0 (default), inner = displayId 1.
    //   Old MIUI/HyperOS2 firmwares used outer=5/inner=0 (legacy LauncherRouteHook).
    // Flip2: outer IS the DEFAULT display (displayId 0) — §34.1.
    object Topology {
        const val FLIP1_OUTER_DISPLAY_ID = 0
        const val FLIP1_INNER_DISPLAY_ID = 1
        const val FLIP2_OUTER_DISPLAY_ID = 0
        const val FLIP2_INNER_DISPLAY_ID = 1
    }

    /** Expected displayId of the outer screen for the current generation. */
    val expectedOuterDisplayId: Int
        get() = when (gen) {
            DeviceGen.FLIP1 -> Topology.FLIP1_OUTER_DISPLAY_ID
            DeviceGen.FLIP2 -> Topology.FLIP2_OUTER_DISPLAY_ID
            else -> -1
        }

    // ── Screen resolutions (min x max, rotation-independent) ──────
    // Outer panel is identical on both generations.
    const val OUTER_W = 1208
    const val OUTER_H = 1392
    // CORRECTED 2026-08-08 by live dumpsys: Flip1 inner is 1080x2340 on
    // HyperOS3 firmware (1555x2508 came from stale notes).
    const val FLIP1_INNER_W = 1080
    const val FLIP1_INNER_H = 2340
    const val FLIP2_INNER_W = 1224
    const val FLIP2_INNER_H = 2912

    // Density guard: both panels ~520-560dpi (3.25-3.5).
    private const val DENSITY_MIN = 3.0f
    private const val DENSITY_MAX = 4.0f

    enum class Screen { OUTER, INNER, UNKNOWN }

    /** Identify inner/outer from raw dimensions + density. Both generations. */
    fun identify(widthPx: Int, heightPx: Int, density: Float): Screen {
        if (density < DENSITY_MIN || density > DENSITY_MAX) return Screen.UNKNOWN
        val w = minOf(widthPx, heightPx)
        val h = maxOf(widthPx, heightPx)
        return when {
            w == OUTER_W && h == OUTER_H -> Screen.OUTER
            w == FLIP1_INNER_W && h == FLIP1_INNER_H -> Screen.INNER
            w == FLIP2_INNER_W && h == FLIP2_INNER_H -> Screen.INNER
            else -> Screen.UNKNOWN
        }
    }

    fun identify(display: Display): Screen {
        val m = DisplayMetrics()
        display.getRealMetrics(m)
        return identify(m.widthPixels, m.heightPixels, m.density)
    }

    fun identify(context: Context): Screen {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return Screen.UNKNOWN
        return identify(wm.defaultDisplay)
    }

    fun isOuter(widthPx: Int, heightPx: Int, density: Float): Boolean =
        identify(widthPx, heightPx, density) == Screen.OUTER

    fun isInner(widthPx: Int, heightPx: Int, density: Float): Boolean =
        identify(widthPx, heightPx, density) == Screen.INNER

    /** True if the given display is the outer screen (by resolution, topology-agnostic). */
    fun isOuterDisplay(display: Display): Boolean = identify(display) == Screen.OUTER

    // ── Identity property probes (hook diagnostics / verification) ─
    /** persist.sys.multi_display_type — root of isFlipDevice(). 4 on both gens. */
    val multiDisplayType: Int by lazy { prop(PROP_MULTI_DISPLAY_TYPE).toIntOrNull() ?: -1 }

    /** persist.sys.muiltdisplay_type — Flip2-only property (16). Empty on Flip1. */
    val muiltdisplayType: Int by lazy { prop(PROP_MUILTDISPLAY_TYPE).toIntOrNull() ?: -1 }

    /**
     * Runtime identity check — mirrors MiuiMultiDisplayTypeInfo.isFlipDevice()
     * without loading the class. Useful to verify hook effect in logs.
     */
    val identitySaysFlip: Boolean get() = (multiDisplayType and 0xFF) == 4

    // ── Class availability probes (per-classloader, R8-safe) ──────
    private val classCache = HashMap<Pair<ClassLoader, String>, Boolean>()

    /**
     * Existence check before hooking a generation-specific class.
     * e.g. exists(loader, "com.android.server.wm.AppContinuityRouterImpl")
     * is false on Flip1 firmware → skip the Flip2-only branch cleanly.
     */
    fun exists(loader: ClassLoader, className: String): Boolean =
        classCache.getOrPut(loader to className) {
            runCatching { loader.loadClass(className); true }.getOrDefault(false)
        }

    /** Load the class if present, else null (for optional cross-gen targets). */
    fun find(loader: ClassLoader, className: String): Class<*>? =
        runCatching { loader.loadClass(className) }.getOrNull()

    /** Generation-branched class resolution: try Flip2 name first, fall back to Flip1. */
    fun resolve(loader: ClassLoader, flip1Name: String, flip2Name: String): Class<*>? =
        when (gen) {
            DeviceGen.FLIP2 -> find(loader, flip2Name) ?: find(loader, flip1Name)
            else -> find(loader, flip1Name) ?: find(loader, flip2Name)
        }

    /** Dump the full detection result. Call from onModuleLoaded/onSystemServerStarting. */
    fun logInfo() {
        val sb = StringBuilder("═══ DeviceGuard ═══\n")
        sb.append("  gen = $gen (ro.product.device=${prop("ro.product.device")})\n")
        sb.append("  multi_display_type = $multiDisplayType, muiltdisplay_type = $muiltdisplayType\n")
        sb.append("  identitySaysFlip = $identitySaysFlip\n")
        sb.append("  expectedOuterDisplayId = $expectedOuterDisplayId")
        log(sb.toString())
    }

    private fun prop(key: String): String = try {
        Class.forName("android.os.SystemProperties")
            .getDeclaredMethod("get", String::class.java, String::class.java)
            .invoke(null, key, "") as? String ?: ""
    } catch (_: Exception) {
        ""
    }
}
