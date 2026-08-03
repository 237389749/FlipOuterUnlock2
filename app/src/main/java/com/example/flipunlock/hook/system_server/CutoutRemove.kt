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
 * Approach: Parser.parse() in system_server zeros ALL cutout specs for fullscreen.
 * In the camera process, hook DisplayCutout's bounding rect methods to return
 * empty Rect(0,0,0,0) instead of null — camera sees isEmpty()=true → no cutout
 * → no NPE. This avoids the need to pass real cutout data between processes.
 *
 * Camera NPE root cause: zeroed bounds → getBoundingRects() empty →
 * getBoundingRectRight/Left() return null → CamLayoutManagerImpl NPE.
 * Fix: hook those methods to return empty Rect instead of null.
 *
 * Hook #1 (Parser.parse, system_server): zero ALL specs.
 * Hook #2 (DisplayCutout.getBoundingRect*, camera): return empty Rect.
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

    // ── #1 CutoutSpecification.Parser.parse() → zero ALL specs (system_server) ──
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

    // ── #2 DisplayCutout bounding rect methods → empty Rect (camera) ──
    //    Prevents NPE when camera calls getBoundingRectRight/Left() etc.
    //    Returns Rect(0,0,0,0) which isEmpty()=true → camera sees "no cutout".
    private fun hookBoundingRects(classLoader: ClassLoader) {
        val dcClass = classLoader.loadClass("android.view.DisplayCutout")
        val emptyRect = Rect(0, 0, 0, 0)
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
