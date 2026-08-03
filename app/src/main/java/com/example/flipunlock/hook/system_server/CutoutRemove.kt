package com.example.flipunlock.hook.system_server

import android.graphics.Insets
import android.graphics.Path
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Remove the outer-screen display cutout so windows lay out across the full
 * 1208px width instead of being clipped to the cutout-safe area (~810px).
 *
 * Approach: hook Parser.parse() with SVG string filter to match only the
 * outer display cutout spec. Zero insets (fullscreen) + path (no visible
 * cutout), keep bounds intact (camera needs non-empty bounding rects).
 *
 * Camera NPE lesson: zeroing bounds to Rect(0,0,0,0) makes them empty →
 * DisplayCutout.getBoundingRects() returns empty list → getBoundingRectRight/
 * Left() return null → camera NPE in CamLayoutManagerImpl. Keeping bounds
 * avoids this.
 *
 * Hook #1 (Parser.parse): string-filter outer spec, zero insets+path only.
 * Hook #2 (getLayoutInDisplayCutoutMode → ALWAYS): defense-in-depth.
 *
 * Toggle: persist.flipunlock.display.cutout (default true)
 * Process: system_server
 */
object CutoutRemove {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayCutout) {
            log("CutoutRemove: DISABLED by persist.flipunlock.display.cutout")
            return
        }
        log("CutoutRemove: setting up")
        safeHook("CutoutRemove") {
            hookCutoutParser(param.classLoader)
            forceCutoutModeAlways(param.classLoader)
        }
    }

    // ── #1 CutoutSpecification.Parser.parse() → zero OUTER spec insets+path only ──
    //    String filter: "M 604,664" / "@bind_right_cutout" = outer display cutout
    //    from config_secondaryBuiltInDisplayCutout. Keep bounds intact for camera.
    private fun hookCutoutParser(classLoader: ClassLoader) {
        runCatching {
            val parserClass = classLoader.loadClass(
                "android.view.CutoutSpecification\$Parser")
            val parseMethod = parserClass.method("parse", String::class.java)
            hook(parseMethod, after { chain, result ->
                val spec = result ?: return@after result
                val originalSpec = chain.args[0] as? String ?: return@after result
                if (originalSpec.contains("M 604,664") ||
                    originalSpec.contains("@bind_right_cutout")) {
                    // Zero insets → no window clipping → fullscreen
                    spec.setField("mInsets", Insets.of(0, 0, 0, 0))
                    // Clear path → no visible cutout area
                    spec.setField("mPath", Path())
                    // Keep bounds intact → getBoundingRectRight/Left() return non-null Rect for camera
                }
                result
            })
            log("CutoutRemove: Parser.parse → zero outer display cutout insets+path only (bounds kept)")
        }.onFailure { log("CutoutRemove: Parser.parse hook failed", it) }
    }

    // ── #2 WindowLayoutStubImpl.getLayoutInDisplayCutoutMode() → ALWAYS (3) ──
    //    MIUI gate for computeFrames() cutout clipping; 3 = lay out into the
    //    cutout area unconditionally (defense-in-depth + centered content fix).
    private fun forceCutoutModeAlways(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("android.view.WindowLayoutStubImpl")
            val method = cls.method(
                "getLayoutInDisplayCutoutMode",
                android.view.WindowManager.LayoutParams::class.java)
            hook(method, replaceResult(3))  // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            log("CutoutRemove: getLayoutInDisplayCutoutMode → ALWAYS (3)")
        }.onFailure { log("CutoutRemove: getLayoutInDisplayCutoutMode hook failed", it) }
    }
}
