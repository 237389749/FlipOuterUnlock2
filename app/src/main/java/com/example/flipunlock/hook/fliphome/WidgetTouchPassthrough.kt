package com.example.flipunlock.hook.fliphome

import android.view.View
import android.view.WindowManager
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field

/**
 * Make the fliphome widget overlay window touch-transparent so touches pass
 * through to the app below instead of being intercepted by the widget.
 *
 * Logic chain (refMD: Gesture_Widget_Overlay.md §2, §3):
 *
 *   WatchOverlayGroupView.init()
 *     → lp.flags = 8519688
 *         (FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN | ...)
 *         — NOTE: FLAG_NOT_TOUCHABLE is NOT set, so the window IS touchable
 *     → paramsForRotation = [lp2, lp2, lp3, lp3]   (per-rotation copies)
 *   WatchOverlayWindow.showHideWindow()
 *     → wm.addView(groupView, getLayoutParams())
 *     → window registered as touchable → intercepts every touch in its bounds
 *       → the app underneath is unreachable in that area
 *
 * Fix (single upstream point): right after the view is CONSTRUCTED — every
 * constructor chains to the one whose LAST statement is init(), which builds
 * mLayoutParams and its paramsForRotation copies — add FLAG_NOT_TOUCHABLE to
 * the stored params AND to each per-rotation copy. InputDispatcher then skips
 * this window entirely and delivers touches to the window below (the app).
 * The widget may still be drawn, but it no longer blocks interaction.
 *
 * Why hook the CONSTRUCTOR rather than init(): on the current ROM init()
 * still exists in the dex, but R8 may inline it into the constructor so a
 * hook on init() never fires. The constructor always executes and cannot be
 * inlined away, making it the robust upstream point.
 *
 * Deliberately separate from widget drawing removal:
 *   - This feature  → widget can stay visible, touches pass through
 *   - Drawing removal → widget is hidden entirely (GONE / ADD→REMOVE / block addView)
 *
 * Defensive hooks (dispatchTouchEvent→false, onInputMonitorEvent→false, 1×1
 * size) are NOT included — add only if testing shows touches still leak.
 *
 * Process: com.miui.fliphome
 */
object WidgetTouchPassthrough : BaseHook() {

    override val targetPackages = listOf("com.miui.fliphome")

    /** Cached handle to the MIUI-specific `paramsForRotation` array on
     *  WindowManager.LayoutParams (populated reflectively by init()). */
    private val paramsForRotationField: Field? by lazy {
        runCatching {
            WindowManager.LayoutParams::class.java
                .getDeclaredField("paramsForRotation")
                .apply { isAccessible = true }
        }.getOrNull()
    }

    override fun setupHooks(param: PackageReadyParam) {
        val clazz = findGroupViewClass(param.classLoader) ?: run {
            log("WidgetTouch: WatchOverlayGroupView class not found")
            return
        }
        // Hook every constructor (after). All constructors chain to the one
        // whose last statement is init(), so when any constructor returns the
        // mLayoutParams (and paramsForRotation copies) are fully built. This
        // is robust against R8 inlining/renaming init() — the constructor
        // always runs and cannot be inlined away, whereas an inlined init()
        // would never trigger a hook placed on it.
        runCatching {
            var hooked = 0
            clazz.declaredConstructors.forEach { ctor ->
                runCatching {
                    ctor.isAccessible = true
                    hook(ctor, after { chain, result ->
                        val view = chain.thisObject as? View ?: return@after result
                        makeTouchTransparent(view)
                        result
                    })
                    hooked++
                }
            }
            log("WidgetTouch: $hooked constructor(s) hooked → FLAG_NOT_TOUCHABLE")
        }.onFailure { log("WidgetTouch: constructor hook failed", it) }

        // Defensive choke point: showHideWindow() registers the window via
        // wm.addView(groupView, groupView.getLayoutParams()). Forcing the flag
        // on the returned params guarantees the window is added touch-
        // transparent even if the constructor hook were somehow bypassed.
        // NOTE: uses the hook's `result` (NOT view.layoutParams) to avoid
        // re-entrant recursion through the hooked getLayoutParams().
        runCatching {
            val glp = clazz.getDeclaredMethod("getLayoutParams")
            hook(glp, after { chain, result ->
                forceNotTouchable(result as? WindowManager.LayoutParams)
                result
            })
            log("WidgetTouch: getLayoutParams() guarded → FLAG_NOT_TOUCHABLE")
        }.onFailure { log("WidgetTouch: getLayoutParams hook failed", it) }
    }

    /**
     * HyperOS 1/2/3 obfuscate the ui sub-package differently.
     * The current ROM (verified via dexdump of the installed APK) uses the
     * un-prefixed `widget.ui` package; keep the p006ui/p014ui variants for
     * older ROMs.
     */
    private fun findGroupViewClass(cl: ClassLoader): Class<*>? {
        val variants = listOf(
            "com.miui.fliphome.widget.p006ui.WatchOverlayGroupView",  // HyperOS 1
            "com.miui.fliphome.widget.p014ui.WatchOverlayGroupView",  // HyperOS 2/3
            "com.miui.fliphome.widget.ui.WatchOverlayGroupView",      // fallback
        )
        for (name in variants) {
            runCatching { return cl.loadClass(name) }
        }
        return null
    }

    /**
     * Add FLAG_NOT_TOUCHABLE to the view's stored window params (via its
     * overridden getLayoutParams) and to the hidden per-rotation copies, so
     * every addView uses touch-transparent params.
     */
    private fun makeTouchTransparent(view: View) {
        forceNotTouchable(view.layoutParams as? WindowManager.LayoutParams)
    }

    /**
     * Set FLAG_NOT_TOUCHABLE on the given params and on each per-rotation
     * copy. Idempotent; logs only when the flag is newly added (safe to call
     * from the frequently-invoked getLayoutParams() hook).
     */
    private fun forceNotTouchable(lp: WindowManager.LayoutParams?) {
        lp ?: return
        runCatching {
            val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            var changed = false
            if (lp.flags and flag == 0) {
                lp.flags = lp.flags or flag
                changed = true
            }
            paramsForRotationField?.let { f ->
                (f.get(lp) as? Array<*>)
                    ?.filterIsInstance<WindowManager.LayoutParams>()
                    ?.forEach { rp ->
                        if (rp.flags and flag == 0) {
                            rp.flags = rp.flags or flag
                            changed = true
                        }
                    }
            }
            if (changed) log("WidgetTouch: FLAG_NOT_TOUCHABLE applied to overlay window")
        }.onFailure { log("WidgetTouch: forceNotTouchable failed", it) }
    }
}
