package com.example.flipunlock.hook.system_server

import android.graphics.Insets
import android.graphics.Path
import android.graphics.Rect
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

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
 * Hook #1 (Parser.parse): zero every bound / inset / path on the parsed
 *   spec. Catches every cutout parsed after onSystemServerStarting (display
 *   bring-up happens later), so the system never builds a real cutout.
 *
 * Hook #2 (DisplayContent.getDisplayInfo): force mDisplayInfo.displayCutout
 *   to NO_CUTOUT on every read. The boot display's cutout can be created &
 *   cached before LSPosed loads, so hooking creation alone is not enough —
 *   this clears the existing cached value and keeps it cleared. Because it
 *   mutates the mDisplayInfo field itself, everything that later reads the
 *   display info (InsetsState population, app DisplayInfo parcels, SystemUI)
 *   sees NO_CUTOUT.
 *
 * Zeroing the cutout at the source cascades everywhere — no per-app API
 * hooks (Display.getCutout / WindowInsets.getDisplayCutout) are needed:
 * apps read the cutout from the display info produced here. Add them only
 * if some app caches a stale cutout or queries a MIUI-specific source.
 *
 * Hook #3 (WindowLayoutStubImpl.getLayoutInDisplayCutoutMode → ALWAYS): the
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
            hookCutoutParser(param.classLoader)
            clearDisplayInfoCutout(param.classLoader)
            forceCutoutModeAlways(param.classLoader)
        }
    }

    // ── #1 CutoutSpecification.Parser.parse() → zero the parsed spec ──
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
            log("CutoutRemove: Parser.parse → zero cutout spec")
        }.onFailure { log("CutoutRemove: Parser.parse hook failed", it) }
    }

    // ── #2 DisplayContent.getDisplayInfo() → NO_CUTOUT on every read ──
    private fun clearDisplayInfoCutout(classLoader: ClassLoader) {
        runCatching {
            val noCutout = classLoader.loadClass("android.view.DisplayCutout")
                .getDeclaredField("NO_CUTOUT")
                .apply { isAccessible = true }
                .get(null)
                ?: run { log("CutoutRemove: NO_CUTOUT is null"); return }
            val dcClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
            val method = dcClass.getDeclaredMethod("getDisplayInfo")
            method.isAccessible = true
            hook(method, before { chain ->
                chain.thisObject.getField("mDisplayInfo")
                    ?.setField("displayCutout", noCutout)
            })
            log("CutoutRemove: DisplayContent.getDisplayInfo → NO_CUTOUT")
        }.onFailure { log("CutoutRemove: getDisplayInfo hook failed", it) }
    }

    // ── #3 WindowLayoutStubImpl.getLayoutInDisplayCutoutMode() → ALWAYS (3) ──
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
