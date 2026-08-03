package com.example.flipunlock.hook.system_server

import android.graphics.Insets
import android.graphics.Path
import android.graphics.Rect
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Constructor

/**
 * Remove the outer-screen display cutout so windows lay out across the full
 * 1208px width instead of being clipped to the cutout-safe area (~810px).
 *
 * Why it matters: the Mix Flip outer screen reports a large rectangular
 * cutout Rect(0,0,398,728) (bezel + camera island, parsed from
 * config_secondaryBuiltInDisplayCutout). WMS clips every window's parent
 * frame by displayCutoutSafe and SystemUI's HideDisplayCutoutOrganizer
 * crops the display surface — together they shrink the usable width to
 * ~810px and shift centered content. Removing the cutout at the source
 * restores the full screen, a prerequisite for testing everything else
 * (widget touch passthrough, app layout, toast centering).
 *
 * Logic chain (refMD: DisplayCutout.md §1–§3):
 *
 *   config_secondaryBuiltInDisplayCutout = "M 604,664 … @bind_right_cutout"
 *     → CutoutSpecification.Parser.parse(spec)            ← hook #1 (source)
 *       → CutoutSpecification { mLeft/Top/Right/BottomBound, mInsets, mPath }
 *         → DisplayCutout → DisplayContent.mDisplayInfo.displayCutout
 *           ├→ InsetsState.displayCutoutSafe → WindowLayout.computeFrames
           │    clips every window's parent frame
 *           └→ SystemUI HideDisplayCutoutOrganizer → SurfaceFlinger display crop
 *
 * Hook #1 (Parser.parse): zero ALL bounds/insets/path on every parsed spec.
 *
 * Hook #2 (pathAndDisplayCutoutFromSpec): the single choke point — ALL cutout
 *   paths converge here. Return (null, NO_CUTOUT) to skip entire pipeline.
 *
 * Hook #3 (Display.getCutout): return NO_CUTOUT for any direct queries.
 *
 * Hook #4 (Display.getFlipFoldedCutout): return null for MIUI-specific queries.
 *
 * Hook #5 (WindowLayoutStubImpl.getLayoutInDisplayCutoutMode → ALWAYS): the
 *   MIUI gate WMS's computeFrames() consults to decide whether to clip a
 *   window's parent frame by the cutout-safe area. With an empty cutout the
 *   clip is a no-op, but forcing mode=3 (LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS)
 *   guarantees computeFrames() skips ALL cutout clipping regardless of a
 *   window's own mode (defense-in-depth) and fixes centered content (e.g.
 *   toasts) that mode=0 would shift. Native MIUI only upgrades mode 1→3 when
 *   folded; we force 3 for every window. The class is in miui-framework.jar
 *   (boot classpath), so hooking it in system_server covers all windows.
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
            val noCutout = constructNoCutout(param.classLoader)
            hookCutoutParser(param.classLoader)
            if (noCutout != null) {
                hookPathAndDisplayCutoutFromSpec(param.classLoader, noCutout)
                hookDisplayGetCutout(param.classLoader, noCutout)
                hookGetFlipFoldedCutout(param.classLoader)
            } else {
                log("CutoutRemove: NO_CUTOUT unavailable, skipping Display-level hooks")
            }
            forceCutoutModeAlways(param.classLoader)
        }
    }

    // ── #1 CutoutSpecification.Parser.parse() → zero ALL specs ──
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

    // ── #2 DisplayCutout.pathAndDisplayCutoutFromSpec() → (null, NO_CUTOUT) ──
    //    The single choke point — ALL cutout paths converge here.
    private fun hookPathAndDisplayCutoutFromSpec(classLoader: ClassLoader, noCutout: Any) {
        runCatching {
            val dcClass = classLoader.loadClass("android.view.DisplayCutout")
            val method = dcClass.getDeclaredMethod(
                "pathAndDisplayCutoutFromSpec",
                Path::class.java, Rect::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            hook(method, replaceResult(android.util.Pair(null, noCutout)))
            log("CutoutRemove: pathAndDisplayCutoutFromSpec → (null, NO_CUTOUT)")
        }.onFailure { log("CutoutRemove: pathAndDisplayCutoutFromSpec hook failed", it) }
    }

    // ── #3 Display.getCutout() → NO_CUTOUT ──
    private fun hookDisplayGetCutout(classLoader: ClassLoader, noCutout: Any) {
        runCatching {
            val method = android.view.Display::class.java.getMethod("getCutout")
            hook(method, replaceResult(noCutout))
            log("CutoutRemove: Display.getCutout → NO_CUTOUT")
        }.onFailure { log("CutoutRemove: Display.getCutout hook failed", it) }
    }

    // ── #4 Display.getFlipFoldedCutout() → null ──
    private fun hookGetFlipFoldedCutout(classLoader: ClassLoader) {
        runCatching {
            val method = android.view.Display::class.java.getMethod("getFlipFoldedCutout")
            hook(method, replaceResult(null))
            log("CutoutRemove: Display.getFlipFoldedCutout → null")
        }.onFailure { log("CutoutRemove: getFlipFoldedCutout hook failed (may not exist)") }
    }

    // ── Construct NO_CUTOUT via reflection ──
    private fun constructNoCutout(classLoader: ClassLoader): Any? {
        // Try static field first
        runCatching {
            val f = classLoader.loadClass("android.view.DisplayCutout")
                .getDeclaredField("NONE")
                .apply { isAccessible = true }
            return f.get(null)
        }
        // Brute-force: find a constructor and pass null/zero args
        runCatching {
            val dcClass = classLoader.loadClass("android.view.DisplayCutout")
            val ctors = dcClass.declaredConstructors
            for (ctor in ctors.sortedBy { it.parameterCount }) {
                ctor.isAccessible = true
                val args = ctor.parameterTypes.map { t ->
                    when {
                        t == Int::class.javaPrimitiveType || t == Integer::class.java -> 0
                        t == Long::class.javaPrimitiveType -> 0L
                        t == Boolean::class.javaPrimitiveType -> false
                        t == Float::class.javaPrimitiveType -> 0f
                        t == Insets::class.java -> Insets.of(0, 0, 0, 0)
                        t == Rect::class.java -> Rect(0, 0, 0, 0)
                        t == Path::class.java -> Path()
                        t == List::class.java -> emptyList<Any>()
                        else -> null
                    }
                }.toTypedArray()
                return ctor.newInstance(*args)
            }
        }
        return null
    }

    // ── #5 WindowLayoutStubImpl.getLayoutInDisplayCutoutMode() → ALWAYS (3) ──
    //    MIUI gate for computeFrames() cutout clipping; 3 = lay out into the
    //    cutout area unconditionally (fixes centered content + defense-in-depth).
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
