package com.example.flipunlock.hook.system_server

import android.graphics.Insets
import android.graphics.Path
import android.graphics.Rect
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Remove the display cutout so windows lay out across the full width.
 *
 * Approach:
 * - system_server: Parser.parse() zeros ALL fields (mInsets + mPath + all bounds)
 *   for every spec — no string filter.
 * - Camera process: hook Display.getCutout() → return valid DisplayCutout with
 *   zero insets + zero bounds. Camera code (l3.t.p / C11138t.mo18139p) accesses
 *   DisplayCutout via Optional chain; if getCutout() returns null → NPE on Rect.right.
 *   Fix: return non-null DisplayCutout so Optional.ifPresent() executes.
 *
 * Hook #1 (Parser.parse, system_server): zero ALL fields (mInsets + mPath + bounds).
 * Hook #2 (Display.getCutout(), camera): return valid DisplayCutout (zero insets + zero bounds).
 * Hook #3 (DisplayCutout.getBoundingRect*, camera): return empty Rect (defense).
 * Hook #4 (getLayoutInDisplayCutoutMode → ALWAYS): defense-in-depth.
 * Hook #5 (InsetsState.getDisplayCutoutSafe → full bounds): fix cached boot-time cutout
 *   in global InsetsState that Parser.parse cannot reach. Without this, computeFrames()
 *   narrows parent frame to 810px → toast/keyboard shifted left.
 * Hook #6 (calculateDisplayCutoutForRotation → NO_CUTOUT): prevent rotation from
 *   recreating cutout via RotationCache (bypasses Parser.parse).
 *   refMD: DisplayCutout.md §17, §19
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
            hookGetDisplayCutoutSafe(param.classLoader)
            hookCalculateDisplayCutoutForRotation(param.classLoader)
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

    // ── #1 CutoutSpecification.Parser.parse() → zero ALL fields ──
    //    Zero mInsets (fullscreen), mPath (hide cutout), and all bounds.
    //    No string filter — applies to all specs.
    //    Camera is protected by hookDisplayGetCutout (returns valid DisplayCutout).
    private fun hookCutoutParser(classLoader: ClassLoader) {
        runCatching {
            val parserClass = classLoader.loadClass(
                "android.view.CutoutSpecification\$Parser")
            val parseMethod = parserClass.method("parse", String::class.java)
            hook(parseMethod, after { chain, result ->
                val spec = result ?: return@after result
                spec.setField("mInsets", Insets.of(0, 0, 0, 0))
                spec.setField("mPath", Path())
                spec.setField("mLeftBound", Rect(0, 0, 0, 0))
                spec.setField("mRightBound", Rect(0, 0, 0, 0))
                spec.setField("mTopBound", Rect(0, 0, 0, 0))
                spec.setField("mBottomBound", Rect(0, 0, 0, 0))
                log("CutoutRemove: Parser.parse → zeroed ALL fields (mInsets + mPath + bounds)")
                result
            })
            log("CutoutRemove: Parser.parse → full zero enabled (all specs)")
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

    // ── #6 DisplayContent.calculateDisplayCutoutForRotation → NO_CUTOUT ──
    //    Rotation events trigger updateDisplayAndOrientation() which calls
    //    calculateDisplayCutoutForRotation(). This method uses RotationCache
    //    that may return pre-cached real cutout WITHOUT going through
    //    pathAndDisplayCutoutFromSpec → Parser.parse() hook is bypassed.
    //    Fix: return NO_CUTOUT unconditionally to prevent cutout recreation.
    //    refMD: DisplayCutout.md §17, §19
    private fun hookCalculateDisplayCutoutForRotation(classLoader: ClassLoader) {
        runCatching {
            val dcClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
            val method = dcClass.getDeclaredMethod(
                "calculateDisplayCutoutForRotation",
                Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            val displayCutoutClass = classLoader.loadClass("android.view.DisplayCutout")
            val noCutoutField = displayCutoutClass.getDeclaredField("NO_CUTOUT")
            noCutoutField.isAccessible = true
            val noCutout = noCutoutField.get(null)
            hook(method, replaceResult(noCutout))
            log("CutoutRemove: calculateDisplayCutoutForRotation → NO_CUTOUT")
        }.onFailure { log("CutoutRemove: calculateDisplayCutoutForRotation hook failed", it) }
    }

    // ── #5 InsetsState.getDisplayCutoutSafe(Rect) → full bounds ──
    //    The global InsetsState is constructed at boot BEFORE our hooks load.
    //    Its mDisplayCutout supplier returns the boot-time real cutout.
    //    Parser.parse() zeros NEW specs but cannot fix the already-cached InsetsState.
    //    computeFrames() calls getDisplayCutoutSafe() → narrows parent frame to 810px
    //    → Gravity.CENTER_HORIZONTAL centers within 810px (shifted ~199px left).
    //    Fix: after original method runs, restore outBounds to full (-100000..100000).
    //    refMD: DisplayCutout.md §18
    private fun hookGetDisplayCutoutSafe(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("android.view.InsetsState")
            val method = cls.method("getDisplayCutoutSafe", Rect::class.java)
            hook(method, after { chain, _ ->
                val outBounds = chain.args[0] as? Rect
                outBounds?.set(-100000, -100000, 100000, 100000)
                null
            })
            log("CutoutRemove: InsetsState.getDisplayCutoutSafe → full bounds")
        }.onFailure { log("CutoutRemove: getDisplayCutoutSafe hook failed", it) }
    }
}
