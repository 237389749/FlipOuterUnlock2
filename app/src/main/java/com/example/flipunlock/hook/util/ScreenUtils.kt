package com.example.flipunlock.hook.util

import android.content.Context
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/**
 * Inner/outer screen identification via resolution + density.
 *
 * MIX Flip screen specs:
 *   Outer (cover):  1208 x 1392, density ~3.5 (560dpi)
 *   Inner (main):   1555 x 2508, density ~3.5 (560dpi)
 *
 * Usage: call [identify] with a Display or DisplayMetrics to determine
 * which physical screen we're dealing with. All hook files should use
 * this utility instead of hardcoding display IDs or dimensions.
 *
 * The outer screen is identified by its distinctive width (1208) combined
 * with height (1392). The inner screen has width 1555 and height 2508.
 * Density is used as a secondary discriminator for robustness across
 * potential future devices or scaling configurations.
 */
object ScreenUtils {

    // ── MIX Flip outer (cover) screen ──────────────────────────────
    const val OUTER_WIDTH = 1208
    const val OUTER_HEIGHT = 1392

    // ── MIX Flip inner (main) screen ───────────────────────────────
    const val INNER_WIDTH = 1555
    const val INNER_HEIGHT = 2508

    // ── Density threshold ──────────────────────────────────────────
    // Both screens are ~560dpi (density 3.5). Use range for tolerance.
    const val DENSITY_MIN = 3.0f
    const val DENSITY_MAX = 4.0f

    enum class ScreenType {
        OUTER,      // Cover screen (1208x1392)
        INNER,      // Main foldable screen (1555x2508)
        UNKNOWN     // Not a recognized MIX Flip screen
    }

    /**
     * Identify screen type from raw dimensions and density.
     *
     * @param widthPx   Physical width in pixels (natural orientation)
     * @param heightPx  Physical height in pixels (natural orientation)
     * @param density   Display density (DisplayMetrics.density)
     * @return ScreenType.OUTER, INNER, or UNKNOWN
     */
    fun identify(widthPx: Int, heightPx: Int, density: Float): ScreenType {
        // Normalize: ensure width < height (portrait convention)
        val w = minOf(widthPx, heightPx)
        val h = maxOf(widthPx, heightPx)

        // Density gate: reject non-phone displays (e.g. virtual, cast)
        if (density < DENSITY_MIN || density > DENSITY_MAX) return ScreenType.UNKNOWN

        return when {
            w == OUTER_WIDTH && h == OUTER_HEIGHT -> ScreenType.OUTER
            w == INNER_WIDTH && h == INNER_HEIGHT -> ScreenType.INNER
            else -> ScreenType.UNKNOWN
        }
    }

    /**
     * Identify from a Display object.
     */
    fun identify(display: Display): ScreenType {
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return identify(metrics.widthPixels, metrics.heightPixels, metrics.density)
    }

    /**
     * Identify from a Context's default display.
     */
    fun identify(context: Context): ScreenType {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return ScreenType.UNKNOWN
        return identify(wm.defaultDisplay)
    }

    /**
     * Identify from DisplayMetrics directly.
     */
    fun identify(metrics: DisplayMetrics): ScreenType {
        return identify(metrics.widthPixels, metrics.heightPixels, metrics.density)
    }

    /**
     * Quick check: is this the outer (cover) screen?
     */
    fun isOuterScreen(widthPx: Int, heightPx: Int, density: Float): Boolean {
        return identify(widthPx, heightPx, density) == ScreenType.OUTER
    }

    /**
     * Quick check: is this the inner (main) screen?
     */
    fun isInnerScreen(widthPx: Int, heightPx: Int, density: Float): Boolean {
        return identify(widthPx, heightPx, density) == ScreenType.INNER
    }

    /**
     * Log current screen identification for diagnostics.
     */
    fun logScreenInfo(tag: String, widthPx: Int, heightPx: Int, density: Float) {
        val type = identify(widthPx, heightPx, density)
        log("$tag: screen ${widthPx}x${heightPx} density=$density → $type")
    }
}
