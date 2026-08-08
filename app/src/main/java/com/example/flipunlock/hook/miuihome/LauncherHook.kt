package com.example.flipunlock.hook.miuihome

import android.os.Message
import android.view.View
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * miuihome bottom gesture diagnostics + repair (outer screen, new topology).
 *
 * Context (refMD FoldState_Device_Identity.md §40, Hook_Chain_Map.md §6):
 *   Old project needed an 8-gate fix for bottom gestures on the outer screen.
 *   The core deadlock (Gate 6) was caused by display routing mismatch — Shell
 *   transitions locate animation roots by displayId (findRootIndex), and the
 *   old topology put the outer screen on display 5, so the recents animation
 *   callback (msg 11) never arrived.
 *
 *   DisplayTopologyHook now pins state=0 with outer = display 0 = DEFAULT,
 *   so that root cause is structurally gone. This hook therefore starts in
 *   DIAGNOSTIC mode:
 *
 *   - Gate 1 (isInSFDeviceFoldedMode): OBSERVE ONLY — should now be false
 *     naturally (identity spoofed + default display). If logs show true,
 *     NavStubView is never created and we escalate to forcing false.
 *   - Gate 3 (getIsUseMiuiHomeAsDefaultHome): OBSERVE ONLY — default home is
 *     now com.miui.home, so it should read true naturally.
 *   - Gate 4 (touch region emptied on app switch): IMPLEMENTED — topology
 *     independent, SystemUI flags still clear mDisableHomeRecents.
 *   - Gate 6 (recents animation deadlock): msg 11 arrival is LOGGED; the
 *     legacy bypass is OFF by default (persist.flipunlock.gesture.bypass).
 *
 * Verdict after reboot:
 *   msg 11 arrives → native animation pipeline alive, no bypass needed.
 *   msg 11 still missing → new firmware has another display gate; enable
 *   the bypass switch to fall back to the old direct-home/recents path.
 */
object LauncherHook : BaseHook() {
    override val targetPackages = listOf("com.miui.home")

    private const val MAX_DIAG_LOGS = 15

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.gestureHome) { log("LauncherHook: DISABLED by persist.flipunlock.gesture.home"); return }
        log("LauncherHook: setup (bypass=${Config.gestureBypass})")
        observeFoldedModeGate(param)
        observeDefaultHomeGate(param)
        hookTouchRegionGuard(param)
        hookAnimationCallbackDiag(param)
        if (Config.gestureBypass) {
            hookBypassRecentsAnimation(param)
        }
    }

    // ── Gate 1 (OBSERVE): physical-fold check that removes NavStubView ──
    //
    // SpecialFDeviceGestureHelper.isInSFDeviceFoldedMode() → true makes
    // BaseRecentsImpl.createAndAddNavStubView() return immediately.
    // Under the new topology + identity spoof it should read false on its own.
    private fun observeFoldedModeGate(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.home.recents.SpecialFDeviceGestureHelper")
            val method = cls.getDeclaredMethod("isInSFDeviceFoldedMode")
            method.isAccessible = true
            var count = 0
            hook(method) { chain ->
                val result = chain.proceed()
                if (count < MAX_DIAG_LOGS) {
                    count++
                    log("LauncherHook DIAG Gate1: isInSFDeviceFoldedMode → $result")
                }
                result
            }
            log("LauncherHook: Gate 1 observer installed")
        }.onFailure { log("LauncherHook: Gate 1 observer failed", it) }
    }

    // ── Gate 3 (OBSERVE): miuihome default-home trust gate ──
    //
    // BaseRecentsImpl.getIsUseMiuiHomeAsDefaultHome() — false kills NavStubView
    // creation and event dispatch. Default home is now com.miui.home (continuity
    // redirect dead), so it should read true naturally.
    private fun observeDefaultHomeGate(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.home.recents.BaseRecentsImpl")
            val method = cls.getDeclaredMethod("getIsUseMiuiHomeAsDefaultHome")
            method.isAccessible = true
            var count = 0
            hook(method) { chain ->
                val result = chain.proceed()
                if (result == false && count < MAX_DIAG_LOGS) {
                    count++
                    log("LauncherHook DIAG Gate3: getIsUseMiuiHomeAsDefaultHome → FALSE ★ gestures will die")
                }
                result
            }
            log("LauncherHook: Gate 3 observer installed")
        }.onFailure { log("LauncherHook: Gate 3 observer failed", it) }
    }

    // ── Gate 4 (IMPLEMENTED): keep bottom touch region alive in apps ──
    //
    // NavStubView.onSystemUiFlagsChanged(flags): SystemUI sends flags with
    // home+overview disabled on app switch → mDisableHomeRecents=true →
    // mUseEmptyTouchableRegion=true → bottom touch region becomes (0,0,0,0).
    // Topology independent — always needed.
    private fun hookTouchRegionGuard(param: PackageReadyParam) {
        runCatching {
            val navClass = param.classLoader.loadClass(
                "com.miui.home.recents.NavStubView")
            val method = navClass.getDeclaredMethod("onSystemUiFlagsChanged",
                Long::class.javaPrimitiveType!!)
            method.isAccessible = true
            val disableField = navClass.getDeclaredField("mDisableHomeRecents")
            disableField.isAccessible = true
            val emptyField = navClass.getDeclaredField("mUseEmptyTouchableRegion")
            emptyField.isAccessible = true

            hook(method, after { chain, result ->
                runCatching {
                    val obj = chain.thisObject
                    if (disableField.getBoolean(obj)) {
                        disableField.setBoolean(obj, false)
                        emptyField.setBoolean(obj, false)
                        log("LauncherHook Gate4: forced mDisableHomeRecents=false")
                    }
                }
                result
            })
            log("LauncherHook: Gate 4 installed (touch region guard)")
        }.onFailure { log("LauncherHook: Gate 4 failed", it) }
    }

    // ── Gate 6 (DIAG): does the Shell transition callback ever arrive? ──
    //
    // The decisive question (refMD §40). Two observation points:
    //   1. GestureStateMachine inner states' processMessage: msg 11 =
    //      onRecentsAnimationStart (the awaited callback), msg 12 = timeout.
    //   2. NavStubView.isNeedStopBecauseRecentsRemoteAnimStartFailed — if true,
    //      showRecents()/startAppToHomeAnim() bail out early.
    private fun hookAnimationCallbackDiag(param: PackageReadyParam) {
        runCatching {
            val smClass = param.classLoader.loadClass(
                "com.miui.home.recents.GestureStateMachine")
            var msg11Count = 0
            var msg12Count = 0
            var installCount = 0
            for (stateClass in smClass.declaredClasses) {
                val pm = runCatching {
                    stateClass.getDeclaredMethod("processMessage", Message::class.java)
                }.getOrNull() ?: continue
                pm.isAccessible = true
                hook(pm) { chain ->
                    val what = (chain.args[0] as? Message)?.what ?: -1
                    when (what) {
                        11 -> if (msg11Count < MAX_DIAG_LOGS) {
                            msg11Count++
                            log("LauncherHook DIAG ★★ msg 11 (onRecentsAnimationStart) ARRIVED in ${stateClass.simpleName} — native animation alive")
                        }
                        12 -> if (msg12Count < MAX_DIAG_LOGS) {
                            msg12Count++
                            log("LauncherHook DIAG ★ msg 12 (TIMEOUT, callback never came) in ${stateClass.simpleName}")
                        }
                    }
                    chain.proceed()
                }
                installCount++
            }
            log("LauncherHook: Gate 6 diag installed on $installCount state classes")
        }.onFailure { log("LauncherHook: Gate 6 diag failed", it) }

        runCatching {
            val navClass = param.classLoader.loadClass(
                "com.miui.home.recents.NavStubView")
            val method = navClass.getDeclaredMethod(
                "isNeedStopBecauseRecentsRemoteAnimStartFailed")
            method.isAccessible = true
            var count = 0
            hook(method) { chain ->
                val result = chain.proceed()
                if (result == true && count < MAX_DIAG_LOGS) {
                    count++
                    log("LauncherHook DIAG Gate6a: isNeedStopBecauseRecentsRemoteAnimStartFailed → TRUE (remote anim dead)")
                }
                result
            }
            log("LauncherHook: Gate 6a observer installed")
        }.onFailure { log("LauncherHook: Gate 6a observer failed", it) }
    }

    // ── Gate 6 BYPASS (legacy path, OFF by default) ──
    //
    // Only enabled via persist.flipunlock.gesture.bypass=true when diagnostics
    // prove the callback still never arrives on new firmware. Ported from the
    // old project's LauncherHook Gates 6a/6b/6c (refMD Hook_Chain_Map.md §6).
    private fun hookBypassRecentsAnimation(param: PackageReadyParam) {
        val navClass = runCatching {
            param.classLoader.loadClass("com.miui.home.recents.NavStubView")
        }.getOrNull() ?: return

        // 6a: unblock showRecents()/startAppToHomeAnim()
        runCatching {
            val method = navClass.getDeclaredMethod(
                "isNeedStopBecauseRecentsRemoteAnimStartFailed")
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("LauncherHook bypass 6a: isNeedStopBecauseRecentsRemoteAnimStartFailed → false")
        }.onFailure { log("LauncherHook bypass 6a failed", it) }

        // 6b: performAppToHome → direct home + haptic (skip finishController hang)
        runCatching {
            val method = navClass.getDeclaredMethod("performAppToHome")
            method.isAccessible = true
            hook(method) { chain ->
                triggerHaptic(param, chain.thisObject)
                runCatching {
                    navClass.getDeclaredMethod("checkAndLauncherHome")
                        .apply { isAccessible = true }.invoke(chain.thisObject)
                }
                null
            }
            log("LauncherHook bypass 6b: performAppToHome → checkAndLauncherHome")
        }.onFailure { log("LauncherHook bypass 6b failed", it) }

        // 6c: on ACTION_UP in AppWaitToDragState, dispatch home/recents by drag progress
        runCatching {
            val smClass = param.classLoader.loadClass(
                "com.miui.home.recents.GestureStateMachine")
            val appWaitClass = smClass.declaredClasses.firstOrNull {
                it.simpleName == "AppWaitToDragState"
            } ?: return
            val processMethod = appWaitClass.getDeclaredMethod("processMessage",
                Message::class.java)
            processMethod.isAccessible = true
            val navField = smClass.getDeclaredField("mNavStubView")
            navField.isAccessible = true

            hook(processMethod) { chain ->
                val msgWhat = (chain.args[0] as? Message)?.what ?: 0
                val result = chain.proceed()  // let original store mMsgUpType
                if (msgWhat in 4..10) {
                    val sm = runCatching {
                        appWaitClass.getDeclaredField("this\$0")
                            .apply { isAccessible = true }.get(chain.thisObject)
                    }.getOrNull() ?: return@hook result
                    val nav = navField.get(sm) ?: return@hook result

                    val dragPer = runCatching {
                        val calc = navClass.getDeclaredField("mCalculator")
                            .apply { isAccessible = true }.get(nav)
                        calc?.javaClass?.getDeclaredMethod("getPer")
                            ?.apply { isAccessible = true }?.invoke(calc) as? Float
                    }.getOrNull() ?: 0f

                    triggerHaptic(param, nav)
                    if (dragPer > 0.5f) {
                        // Long drag → home first, then delayed OVERVIEW transition
                        runCatching {
                            navClass.getDeclaredMethod("checkAndLauncherHome")
                                .apply { isAccessible = true }.invoke(nav)
                        }
                        postDelayedOverview(param, nav)
                        log("LauncherHook bypass 6c: UP per=$dragPer → home+delayed OVERVIEW")
                    } else {
                        runCatching {
                            navClass.getDeclaredMethod("checkAndLauncherHome")
                                .apply { isAccessible = true }.invoke(nav)
                        }
                        log("LauncherHook bypass 6c: UP per=$dragPer → home")
                    }
                }
                result
            }
            log("LauncherHook bypass 6c: ACTION_UP dispatch installed")
        }.onFailure { log("LauncherHook bypass 6c failed", it) }
    }

    /** Phase 1 (300ms): reload task list; Phase 2 (800ms): goToState(OVERVIEW). */
    private fun postDelayedOverview(param: PackageReadyParam, nav: Any) {
        runCatching {
            val navClass = nav.javaClass
            val ctx = (nav as View).context
            val rmClass = param.classLoader.loadClass("com.miui.home.recents.RecentsModel")
            val rmInstance = rmClass.getDeclaredMethod("getInstance",
                android.content.Context::class.java).invoke(null, ctx)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            handler.postDelayed({
                runCatching {
                    rmClass.getDeclaredMethod("notifyRecentTasksChanged")
                        .apply { isAccessible = true }.invoke(rmInstance)
                }
            }, 300L)

            handler.postDelayed({
                runCatching {
                    val launcher = navClass.getDeclaredField("mLauncher")
                        .apply { isAccessible = true }.get(nav) ?: return@runCatching
                    val sm = launcher.javaClass.getMethod("getStateManager").invoke(launcher)
                    val lsClass = param.classLoader.loadClass("com.miui.home.launcher.LauncherState")
                    val overview = lsClass.getDeclaredField("OVERVIEW").get(null)
                    sm.javaClass.methods.firstOrNull { m ->
                        m.name == "goToState" && m.parameterCount == 2 &&
                        m.parameterTypes[1] == Boolean::class.javaPrimitiveType
                    }?.invoke(sm, overview, false)
                }
            }, 800L)
        }.onFailure { log("LauncherHook bypass: postDelayedOverview failed", it) }
    }

    /** Haptic feedback lost by the 6b/6c bypass — original performAppToHome did it. */
    private fun triggerHaptic(param: PackageReadyParam, nav: Any) {
        runCatching {
            val hapticClass = param.classLoader.loadClass(
                "com.miui.home.common.hapticfeedback.HapticFeedbackCompat")
            val instance = hapticClass.getDeclaredMethod("getInstance").invoke(null)
            hapticClass.getDeclaredMethod("performHomeGestureAccessibilitySwitch",
                View::class.java).invoke(instance, nav)
        }
    }
}
