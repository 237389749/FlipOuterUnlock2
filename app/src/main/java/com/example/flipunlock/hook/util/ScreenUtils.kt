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
 * NOTE: This is a utility class available for future use.
 * Hook files should NOT depend on it during early testing —
 * each hook uses its own minimal inline check until the
 * screen identification logic stabilizes.
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
     */
    fun identify(widthPx: Int, heightPx: Int, density: Float): ScreenType {
        val w = minOf(widthPx, heightPx)
        val h = maxOf(widthPx, heightPx)
        if (density < DENSITY_MIN || density > DENSITY_MAX) return ScreenType.UNKNOWN
        return when {
            w == OUTER_WIDTH && h == OUTER_HEIGHT -> ScreenType.OUTER
            w == INNER_WIDTH && h == INNER_HEIGHT -> ScreenType.INNER
            else -> ScreenType.UNKNOWN
        }
    }

    fun identify(display: Display): ScreenType {
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return identify(metrics.widthPixels, metrics.heightPixels, metrics.density)
    }

    fun identify(context: Context): ScreenType {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return ScreenType.UNKNOWN
        return identify(wm.defaultDisplay)
    }

    fun identify(metrics: DisplayMetrics): ScreenType {
        return identify(metrics.widthPixels, metrics.heightPixels, metrics.density)
    }

    fun isOuterScreen(widthPx: Int, heightPx: Int, density: Float): Boolean {
        return identify(widthPx, heightPx, density) == ScreenType.OUTER
    }

    fun isInnerScreen(widthPx: Int, heightPx: Int, density: Float): Boolean {
        return identify(widthPx, heightPx, density) == ScreenType.INNER
    }

    fun logScreenInfo(tag: String, widthPx: Int, heightPx: Int, density: Float) {
        val type = identify(widthPx, heightPx, density)
        log("$tag: screen ${widthPx}x${heightPx} density=$density → $type")
    }
}
