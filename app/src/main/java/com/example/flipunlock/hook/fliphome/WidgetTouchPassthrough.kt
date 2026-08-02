package com.example.flipunlock.hook.fliphome

import android.view.View
import android.view.WindowManager
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

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
 * Fix (single upstream point): right after init() builds the layout params,
 * add FLAG_NOT_TOUCHABLE to the stored params AND to each per-rotation copy.
 * InputDispatcher then skips this window entirely and delivers touches to the
 * window below (the app). The widget may still be drawn, but it no longer
 * blocks interaction.
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

    override fun setupHooks(param: PackageReadyParam) {
        val clazz = findGroupViewClass(param.classLoader) ?: run {
            log("WidgetTouch: WatchOverlayGroupView class not found")
            return
        }
        runCatching {
            val initMethod = clazz.method("init")
            hook(initMethod, after { chain, result ->
                val view = chain.thisObject as? View ?: return@after result
                makeTouchTransparent(view)
                result
            })
            log("WidgetTouch: init() hooked → FLAG_NOT_TOUCHABLE")
        }.onFailure { log("WidgetTouch: init hook failed", it) }
    }

    /** HyperOS 1/2/3 obfuscate the ui sub-package differently. */
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
     * Add FLAG_NOT_TOUCHABLE to the window's stored layout params and to the
     * hidden per-rotation copies, so every addView uses touch-transparent params.
     */
    private fun makeTouchTransparent(view: View) {
        runCatching {
            val lp = view.layoutParams as? WindowManager.LayoutParams
                ?: return
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching {
                val f = WindowManager.LayoutParams::class.java
                    .getDeclaredField("paramsForRotation")
                f.isAccessible = true
                (f.get(lp) as? Array<*>)
                    ?.filterIsInstance<WindowManager.LayoutParams>()
                    ?.forEach { rp ->
                        rp.flags = rp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    }
            }
            log("WidgetTouch: FLAG_NOT_TOUCHABLE applied to overlay window")
        }.onFailure { log("WidgetTouch: makeTouchTransparent failed", it) }
    }
}
