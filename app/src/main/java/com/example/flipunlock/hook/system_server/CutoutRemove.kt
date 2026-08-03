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
 * Approach: Parser.parse() in system_server zeros mInsets (fullscreen layout)
 * and mPath (hide cutout display), but PRESERVES bounds (mLeftBound etc.)
 * so camera can still access non-null bounding rects.
 *
 * Camera NPE root cause: zeroed bounds → DisplayCutout internal Rect fields
 * become null → camera code accessing Rect.right on null → NPE.
 * Fix: preserve bounds in Parser.parse, only zero mInsets + mPath.
 *
 * Hook #1 (Parser.parse, system_server): zero mInsets + mPath, preserve bounds.
 * Hook #2 (DisplayCutout.getBoundingRect*, camera): return empty Rect (defense).
 * Hook #3 (getLayoutInDisplayCutoutMode → ALWAYS): defense-in-depth.
 *
 * Toggle: persist.flipunlock.display.cutout (default true)
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

    /** Called from onPackageReady for camera process. */
    fun hookApp(param: PackageReadyParam) {
        if (!Config.displayCutout) return
        log("CutoutRemove: hookApp in ${param.packageName}")
        safeHook("CutoutRemove") {
            hookBoundingRects(param.classLoader)
            forceCutoutModeAlways(param.classLoader)
        }
    }

    // ── #1 CutoutSpecification.Parser.parse() → zero mInsets + mPath only (system_server) ──
    //    Keep bounds (mLeftBound etc.) intact — camera needs non-null bounding rects.
    private fun hookCutoutParser(classLoader: ClassLoader) {
        runCatching {
            val parserClass = classLoader.loadClass(
                "android.view.CutoutSpecification\$Parser")
            val parseMethod = parserClass.method("parse", String::class.java)
            hook(parseMethod, after { chain, result ->
                val spec = result ?: return@after result
                // Only zero mInsets (fullscreen layout) and mPath (hide cutout display)
                // Keep bounds intact for camera NPE prevention
                spec.setField("mInsets", Insets.of(0, 0, 0, 0))
                spec.setField("mPath", Path())
                result
            })
            log("CutoutRemove: Parser.parse → zero mInsets + mPath (bounds preserved)")
        }.onFailure { log("CutoutRemove: Parser.parse hook failed", it) }
    }

    // ── #2 DisplayCutout getter methods → empty Rect (camera, defense-in-depth) ──
    //    Bounds are preserved in Parser.parse, so camera should work.
    //    These hooks are backup in case camera uses different code paths.
    private fun hookBoundingRects(classLoader: ClassLoader) {
        val dcClass = classLoader.loadClass("android.view.DisplayCutout")
        val emptyRect = Rect(0, 0, 0, 0)
        
        // Hook getter methods as defense-in-depth
        val methods = listOf(
            "getBoundingRectLeft",
            "getBoundingRectRight",
            "getBoundingRectTop",
            "getBoundingRectBottom"
        )
        for (name in methods) {
            runCatching {
                val method = dcClass.getMethod(name)
                hook(method, replaceResult(emptyRect))
                log("CutoutRemove: DisplayCutout.$name → empty Rect")
            }.onFailure { log("CutoutRemove: $name hook failed (may be final)", it) }
        }
        // Also hook getBoundingRects() → empty list
        runCatching {
            val method = dcClass.getMethod("getBoundingRects")
            hook(method, replaceResult(emptyList<Rect>()))
            log("CutoutRemove: DisplayCutout.getBoundingRects → empty list")
        }.onFailure { log("CutoutRemove: getBoundingRects hook failed", it) }
    }

    // ── #3 WindowLayoutStubImpl.getLayoutInDisplayCutoutMode() → ALWAYS (3) ──
    private fun forceCutoutModeAlways(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("android.view.WindowLayoutStubImpl")
            val method = cls.method(
                "getLayoutInDisplayCutoutMode",
                android.view.WindowManager.LayoutParams::class.java)
            hook(method, replaceResult(3))
            log("CutoutRemove: getLayoutInDisplayCutoutMode → ALWAYS (3)")
        }.onFailure { log("CutoutRemove: getLayoutInDisplayCutoutMode hook failed", it) }
    }
}
