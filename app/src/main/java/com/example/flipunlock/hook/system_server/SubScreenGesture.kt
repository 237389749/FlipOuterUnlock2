package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Enable double-tap-to-sleep on the outer screen.
 *
 * MiuiSubScreenMultiFingerGestureManager manages subscreen gestures:
 *   - MiuiSubscreenDoubleTapGesture (double-tap → goToSleep)
 *   - MiuiSubscreenThreeFingerDownGesture (3-finger swipe → screenshot)
 *
 * Two problems on Mix Flip (type 4 / flip):
 *
 *   1. init() gates on isIndependentRearDevice() (type 6 only).
 *      Mix Flip is type 4, so the manager never initializes.
 *      Fix: hook init() to bypass the guard and create the instance directly.
 *
 *   2. The class hardcodes NEED_DISPLAY_ID = 1 everywhere:
 *        - constructor: registerPointerEventListener(this, 1)
 *        - onFocusedWindowChanged: if (displayId != 1) return
 *        - goToSleep(1, eventTime, 6, 0)
 *        - pilferPointers(1)
 *      With DisplayStateHook state=6 (DUAL), outer screen is displayId=0.
 *      Fix: hook each call site to redirect displayId 1↔0.
 *
 * Ref: refMD Gesture_Widget_Overlay.md §11
 */
object SubScreenGesture {

    fun hook(param: SystemServerStartingParam) {
        log("SubScreenGesture: setting up")
        safeHook("SubScreenGesture") {
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.miui.server.input.gesture.multifingergesture.MiuiSubScreenMultiFingerGestureManager")
                val monitorCls = param.classLoader.loadClass(
                    "com.miui.server.input.gesture.MiuiGestureMonitor")

                // ── #1 init(Context): bypass isIndependentRearDevice() gate ──
                // Original: if (!isIndependentRearDevice() || sInstance != null) return;
                // We create the instance directly so the guard is irrelevant.
                val initMethod = cls.method("init", android.content.Context::class.java)
                hook(initMethod) { chain ->
                    val existing = runCatching {
                        cls.field("sInstance").get(null)
                    }.getOrNull()
                    if (existing == null) {
                        val context = chain.args[0] as? android.content.Context
                        if (context != null) {
                            val constructor = cls.getDeclaredConstructor(android.content.Context::class.java)
                            constructor.isAccessible = true
                            val instance = constructor.newInstance(context)
                            cls.field("sInstance").set(null, instance)
                            log("SubScreenGesture: initialized for Mix Flip (bypassed isIndependentRearDevice)")
                        }
                    }
                    chain.proceed()
                }

                // ── #2 registerPointerEventListener(listener, displayId): 1→0 ──
                // Constructor calls registerPointerEventListener(this, 1).
                // Redirect to displayId=0 so the gesture monitor listens on the outer screen.
                val gestureListenerClass = param.classLoader.loadClass(
                    "com.miui.server.input.gesture.MiuiGestureListener")
                val regMethod = monitorCls.getDeclaredMethod(
                    "registerPointerEventListener", gestureListenerClass, Int::class.javaPrimitiveType!!)
                regMethod.isAccessible = true
                hook(regMethod) { chain ->
                    val displayId = chain.args[1] as? Int ?: return@hook chain.proceed()
                    if (displayId == 1) {
                        chain.proceed(chain.args[0], 0)
                        log("SubScreenGesture: registerPointerEventListener displayId 1→0")
                        return@hook
                    }
                    chain.proceed()
                }

                // ── #3 onFocusedWindowChanged(displayId, old, new): accept 0 as 1 ──
                // Original: if (displayId != 1) return;
                // Outer screen is displayId=0, so this always bails out.
                // Fix: when displayId==0, rewrite to 1 so the guard passes.
                val windowStateClass = param.classLoader.loadClass(
                    "com.android.server.policy.WindowManagerPolicy\$WindowState")
                val focusMethod = cls.getDeclaredMethod(
                    "onFocusedWindowChanged",
                    Int::class.javaPrimitiveType!!,
                    windowStateClass,
                    windowStateClass)
                focusMethod.isAccessible = true
                hook(focusMethod) { chain ->
                    val displayId = chain.args[0] as? Int ?: return@hook chain.proceed()
                    if (displayId == 0) {
                        chain.proceed(1, chain.args[1], chain.args[2])
                        log("SubScreenGesture: onFocusedWindowChanged displayId 0→1")
                        return@hook
                    }
                    chain.proceed()
                }

                // ── #4 PowerManager.goToSleep(displayId, time, reason, flags): 1→0 ──
                // MiuiSubscreenDoubleTapGesture hardcodes goToSleep(1, ...).
                // Outer screen is displayId=0, redirect.
                val pmClass = param.classLoader.loadClass("android.os.PowerManager")
                val sleepMethod = pmClass.getDeclaredMethod(
                    "goToSleep",
                    Int::class.javaPrimitiveType!!,
                    Long::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!)
                sleepMethod.isAccessible = true
                hook(sleepMethod) { chain ->
                    val displayId = chain.args[0] as? Int ?: return@hook chain.proceed()
                    if (displayId == 1) {
                        chain.proceed(0, chain.args[1], chain.args[2], chain.args[3])
                        log("SubScreenGesture: goToSleep displayId 1→0")
                        return@hook
                    }
                    chain.proceed()
                }

                // ── #5 pilferPointers(): fix hardcoded displayId=1 ──
                // Static method: sInstance.mMiuiGestureMonitor.pilferPointers(1)
                // Hook the static method to call with 0 instead.
                val pilferMethod = cls.getDeclaredMethod("pilferPointers")
                pilferMethod.isAccessible = true
                hook(pilferMethod) { chain ->
                    // Let original run — it calls pilferPointers(1) internally.
                    // We hook MiuiGestureMonitor.pilferPointers(int) to redirect 1→0.
                    chain.proceed()
                }
                // Hook MiuiGestureMonitor.pilferPointers(int): 1→0
                val monitorPilfer = monitorCls.getDeclaredMethod(
                    "pilferPointers", Int::class.javaPrimitiveType!!)
                monitorPilfer.isAccessible = true
                hook(monitorPilfer) { chain ->
                    val displayId = chain.args[0] as? Int ?: return@hook chain.proceed()
                    if (displayId == 1) {
                        chain.proceed(0)
                        log("SubScreenGesture: pilferPointers displayId 1→0")
                        return@hook
                    }
                    chain.proceed()
                }

                log("SubScreenGesture: all hooks installed")
            }.onFailure { log("SubScreenGesture: setup failed", it) }
        }
    }
}
