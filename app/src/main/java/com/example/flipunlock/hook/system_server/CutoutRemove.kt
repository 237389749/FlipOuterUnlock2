package com.example.flipunlock.hook.system_server

import android.graphics.Insets
import android.graphics.Path
import android.graphics.Rect
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Remove the outer-screen display cutout so windows lay out across the full
 * 1208px width instead of being clipped to the cutout-safe area (~810px).
 *
 * Approach: Parser.parse() is hooked in system_server to zero ALL cutout
 * specs (bounds+insets+path) for full-screen layout. The camera process
 * (com.android.camera) is handled separately via hookApp() in Main.kt —
 * it does NOT install the Parser.parse zeroing hook, so the camera gets
 * real cutout data for its layout calculations (getBoundingRectRight/Left
 * need non-null Rects).
 *
 * Camera NPE lesson: zeroing bounds to Rect(0,0,0,0) makes them empty →
 * DisplayCutout.getBoundingRects() returns empty list → getBoundingRectRight/
 * Left() return null → camera NPE in CamLayoutManagerImpl. Skipping the
 * camera process avoids this.
 *
 * Hook #1 (Parser.parse): zero ALL specs, skip camera process.
 * Hook #2 (getLayoutInDisplayCutoutMode → ALWAYS): defense-in-depth.
 *
 * Toggle: persist.flipunlock.display.cutout (default true)
 * Process: system_server + all scoped app processes
 */
object CutoutRemove {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayCutout) {
            log("CutoutRemove: DISABLED by persist.flipunlock.display.cutout")
            return
        }
        log("CutoutRemove: setting up in system_server")
        safeHook("CutoutRemove") {
            hookCutoutParser(param.classLoader)
            forceCutoutModeAlways(param.classLoader)
        }
    }

    /** Called from onPackageReady for camera process — skip zeroing. */
    fun hookApp(param: PackageReadyParam) {
        if (!Config.displayCutout) return
        log("CutoutRemove: setting up in ${param.packageName} (real cutout preserved)")
        safeHook("CutoutRemove") {
            // Camera process: do NOT install Parser.parse zeroing hook.
            // Only install ALWAYS mode for defense-in-depth.
            forceCutoutModeAlways(param.classLoader)
        }
    }

    // ── #1 CutoutSpecification.Parser.parse() → zero ALL specs ──
    //    Only installed in system_server (not camera process).
    private fun hookCutoutParser(classLoader: ClassLoader) {
        runCatching {
            val parserClass = classLoader.loadClass(
                "android.view.CutoutSpecification\$Parser")
            val parseMethod = parserClass.method("parse", String::class.java)
            hook(parseMethod, after { chain, result ->
                val spec = result ?: return@after result
                spec.setField("mLeftBound", Rect(0, 0, 0, 0))
                spec.setField("mTopBound", Rect(0, 0, 0, 0))
                spec.setField("mRightBound", Rect(0, 0, 0, 0))
                spec.setField("mBottomBound", Rect(0, 0, 0, 0))
                spec.setField("mInsets", Insets.of(0, 0, 0, 0))
                spec.setField("mPath", Path())
                result
            })
            log("CutoutRemove: Parser.parse → zero ALL cutout specs")
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
