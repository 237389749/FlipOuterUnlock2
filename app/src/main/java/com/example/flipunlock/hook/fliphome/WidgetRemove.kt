package com.example.flipunlock.hook.fliphome

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Completely remove the fliphome widget overlay window so it never appears.
 *
 * Logic chain (refMD: Gesture_Widget_Overlay.md §3):
 *
 *   Every show/hide decision funnels through a single choke point:
 *
 *     CheckAppConfigRunnable.postRefreshWindow()  ┐
 *     setSupportWatchForNull()                    ├→ refreshWindow(action, withAnimation)
 *     foreground-app change                       ┘        action: 1=ADD, 2=REMOVE
 *       → showHideWindow(show = action==1, withAnimation)
 *         → wm.addView(groupView)  /  wm.removeViewImmediate(groupView)
 *
 *   showHideWindow() is ONLY called from refreshWindow(), and addView() is
 *   ONLY called from showHideWindow() — so refreshWindow() is the single
 *   upstream funnel for every window add.
 *
 * Fix (single upstream point): rewrite every ADD(1) into REMOVE(2). The
 * window is then never added to WindowManager, so it neither draws nor
 * intercepts touches — the overlay is gone entirely, and the full screen
 * shows the content underneath (the app was always full-screen below it).
 *
 * removeViewImmediate() on a never-attached view is a safe no-op
 * (WindowManagerGlobal.findViewLocked(required=false) returns null), so
 * converting the very first ADD into a REMOVE cannot crash fliphome.
 *
 * Deliberately separate from WidgetTouchPassthrough:
 *   - This feature       → widget never appears at all
 *   - Touch passthrough  → widget stays visible, touches pass through it
 *   (When both are active this one dominates — the window is never added.)
 *
 * Defensive layers (block wm.addView, GONE, 1×1) are NOT included — add
 * only if testing shows the widget still appears via some undocumented path.
 *
 * Process: com.miui.fliphome
 */
object WidgetRemove : BaseHook() {

    override val targetPackages = listOf("com.miui.fliphome")

    private const val ACTION_ADD_WINDOW = 1
    private const val ACTION_REMOVE_WINDOW = 2

    override fun setupHooks(param: PackageReadyParam) {
        runCatching {
            val windowClass = param.classLoader.loadClass(
                "com.miui.fliphome.widget.WatchOverlayWindow")
            val refreshMethod = windowClass.method(
                "refreshWindow",
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!)
            hook(refreshMethod, Hooker { chain ->
                val action = chain.args[0] as Int
                if (action == ACTION_ADD_WINDOW) {
                    log("WidgetRemove: refreshWindow ADD → REMOVE")
                    // libxposed Chain.getArgs() returns an IMMUTABLE list — new
                    // arguments must be passed via proceed(Object[]), NOT by
                    // mutating args in place (that throws
                    // UnsupportedOperationException and silently no-ops the hook).
                    // New args: REMOVE(2), withAnimation=false (immediate).
                    chain.proceed(arrayOf<Any?>(ACTION_REMOVE_WINDOW, false))
                } else {
                    chain.proceed()
                }
            })
            log("WidgetRemove: refreshWindow hooked → widget window never added")
        }.onFailure { log("WidgetRemove: hook failed", it) }
    }
}
