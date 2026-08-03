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
 * Approach:
 * - system_server: Parser.parse() zeros mInsets + mPath for outer screen spec only
 *   (string filter: "M 604,664" / "@bind_right_cutout"), preserves bounds.
 * - Camera process: hook Display.getCutout() → return valid DisplayCutout with
 *   zero insets + zero bounds. Camera code (l3.t.p / C11138t.mo18139p) accesses
 *   DisplayCutout via Optional chain; if getCutout() returns null → NPE on Rect.right.
 *   Fix: return non-null DisplayCutout so Optional.ifPresent() executes.
 *
 * Hook #1 (Parser.parse, system_server): string filter → zero mInsets + mPath, preserve bounds.
 * Hook #2 (Display.getCutout(), camera): return valid DisplayCutout (zero insets + zero bounds).
 * Hook #3 (DisplayCutout.getBoundingRect*, camera): return empty Rect (defense).
 * Hook #4 (getLayoutInDisplayCutoutMode → ALWAYS): defense-in-depth.
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
            hookDisplayGetCutout(param.classLoader)
            hookBoundingRects(param.classLoader)
            forceCutoutModeAlways(param.classLoader)
        }
    }

    // ── #1 CutoutSpecification.Parser.parse() → zero mInsets + mPath for OUTER screen only ──
    //    Use string filtering to match outer screen spec only.
    //    Keep bounds intact for camera NPE prevention.
    private fun hookCutoutParser(classLoader: ClassLoader) {
        runCatching {
            val parserClass = classLoader.loadClass(
                "android.view.CutoutSpecification\$Parser")
            val parseMethod = parserClass.method("parse", String::class.java)
            hook(parseMethod, after { chain, result ->
                val spec = result ?: return@after result
                val specString = chain.args[0] as? String ?: return@after result
                // Only match outer screen spec (from config_secondaryBuiltInDisplayCutout)
                if (specString.contains("M 604,664") || specString.contains("@bind_right_cutout")) {
                    // Only zero mInsets (fullscreen layout) and mPath (hide cutout display)
                    // Keep bounds intact for camera NPE prevention
                    spec.setField("mInsets", Insets.of(0, 0, 0, 0))
                    spec.setField("mPath", Path())
                    log("CutoutRemove: Parser.parse → outer screen spec detected, zeroed mInsets + mPath")
                }
                result
            })
            log("CutoutRemove: Parser.parse → string filtering enabled (outer screen only)")
        }.onFailure { log("CutoutRemove: Parser.parse hook failed", it) }
    }

    // ── #2 Display.getCutout() → valid DisplayCutout (camera, NPE prevention) ──
    //    Camera code (C11138t/l3.t) does:
    //      Optional.ofNullable(display.getCutout()).flatMap(...).ifPresent(consumer)
    //    If getCutout() returns null → Optional empty → field f32924q not set → NPE on Rect.right.
    //    Fix: return a valid DisplayCutout with zero insets + zero bounds.
    //    This ensures Optional chain executes, camera gets non-null bounding rects.
    private fun hookDisplayGetCutout(classLoader: ClassLoader) {
        runCatching {
            val displayClass = classLoader.loadClass("android.view.Display")
            val getCutoutMethod = displayClass.method("getCutout")
            // Create a valid DisplayCutout with zero insets + zero bounds
            // Using public 5-param constructor: DisplayCutout(Insets, Rect, Rect, Rect, Rect)
            val dcClass = classLoader.loadClass("android.view.DisplayCutout")
            val insetsClass = classLoader.loadClass("android.graphics.Insets")
            val intClass = Int::class.javaPrimitiveType!!
            val zeroInsets = insetsClass.method("of", intClass, intClass, intClass, intClass).invoke(null, 0, 0, 0, 0)
            val zeroRect = Rect(0, 0, 0, 0)
            val safeCutout = dcClass.getConstructor(
                insetsClass, Rect::class.java, Rect::class.java, Rect::class.java, Rect::class.java
            ).newInstance(zeroInsets, zeroRect, zeroRect, zeroRect, zeroRect)
            hook(getCutoutMethod, replaceResult(safeCutout))
            log("CutoutRemove: Display.getCutout() → valid DisplayCutout (zero insets + zero bounds)")
        }.onFailure { log("CutoutRemove: Display.getCutout() hook failed", it) }
    }

    // ── #3 DisplayCutout getter methods → empty Rect (camera, defense-in-depth) ──
    //    Bounds are provided by hookDisplayGetCutout, so camera should work.
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

    // ── #4 WindowLayoutStubImpl.getLayoutInDisplayCutoutMode() → ALWAYS (3) ──
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
