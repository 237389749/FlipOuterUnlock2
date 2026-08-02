package com.example.flipunlock.hook.aod

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Always-On Display on the outer (cover) screen when folded.
 *
 * AOD is the most tangled feature in this module (refMD: FoldState_Device_Identity.md
 * §25 AOD/Doze architecture, §26 AOD app layer). Two cooperating sides:
 *
 * ── Framework side (system_server) — keeps the rear dream alive ──
 *   PowerManagerService.handleRearSandman(groupId=1):
 *     if (!mRearAlwaysOnEnabled && wakefulness==3) → sleepPowerGroup (NO AOD)
 *   #1 updateRearDozeSettings(groupId, alwaysOn, isFullAod): force alwaysOn+isFullAod
 *      for groupId 1 so handleRearSandman starts the dream instead of sleeping.
 *   #2 DreamController.stopDream(force, reason): block the "slow to connect"/
 *      "slow to finish" timeout kills for groupId 1 (the AOD dream connects slowly
 *      and would otherwise be torn down after ~5s).
 *   NOTE: MiuiFlipPolicy/DisplayManagerServiceImpl.shouldDeviceBeSleep()→false are
 *   deliberately NOT hooked — blocking sleep prevents the dream from ever starting
 *   (the old project commented them out for exactly this reason).
 *
 * ── App side (com.android.systemui / com.miui.aod) — force the AOD screen state ──
 *   The AOD classes (com.miui.aod.*) live in MIUIAod.apk under a SEPARATE classloader
 *   that onPackageReady's classLoader cannot see. Two layers:
 *   Layer 1 (framework DreamService, visible from SystemUI):
 *     #3 DreamService.setDozeScreenState(int): block OFF states {0,1,3} → force 4
 *        (AOD ON); let {2,4} pass. (v2.3 fix: state 4 = AOD ON must NOT be rewritten
 *        — the old v1 redirected 4→2 and caused a black screen.)
 *     #4 DreamService.onDreamingStarted(): one-shot trigger for Layer 2.
 *   Layer 2 (runtime, via the DozeMachine instance's OWN classloader):
 *     walk the object graph from the DreamService to find com.miui.aod.doze.DozeMachine,
 *     then with its classloader hook DozeMachine.requestState() (redirect
 *     DOZE/DOZE_SUSPEND/FINISH → DOZE_AOD), DozeService.setDozeScreenState() (same map
 *     as #3), DozeHost.isFullAod()→false, and FlipLinkageStyleController
 *     isFlipped()→false / isUsingFlip()→true (neutralize the AOD kill switch in
 *     DozeMachine.resolveIntermediateState()).
 *
 * KNOWN RISKS (refMD §26): on MIX Flip the AOD code runs inside the SystemUI process;
 * the DozeMachine graph walk or the classloader isolation may keep parts of Layer 2
 * from firing. The DozeMachine state flow can also skip DOZE_AOD entirely. This port
 * is faithful to the old project (with the immutable-args and screen-state bugs fixed)
 * and is expected to need on-device iteration.
 *
 * Toggle: persist.flipunlock.display.aod (default true)
 */
object AodHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "com.miui.aod")

    /** Runtime (Layer 2) hooks installed at most once per process. */
    @Volatile
    private var runtimeHooksInstalled = false

    // ── Framework side (system_server) ──────────────────────────────────

    fun hookFramework(param: SystemServerStartingParam) {
        if (!Config.displayAod) {
            log("AodHook: DISABLED by persist.flipunlock.display.aod")
            return
        }
        log("AodHook(framework): setting up")
        safeHook("AodHook") {
            hookUpdateRearDozeSettings(param.classLoader)
            hookStopDream(param.classLoader)
        }
    }

    // ── #1 PowerManagerService.updateRearDozeSettings → alwaysOn + isFullAod ──
    private fun hookUpdateRearDozeSettings(classLoader: ClassLoader) {
        runCatching {
            val pms = classLoader.loadClass("com.android.server.power.PowerManagerService")
            val method = pms.method(
                "updateRearDozeSettings",
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!)
            hook(method) { chain ->
                val groupId = chain.args[0] as? Int
                if (groupId == 1) {
                    // getArgs() is immutable — rewrite via proceed(Object[])
                    chain.proceed(arrayOf<Any?>(1, true, true))
                } else {
                    chain.proceed()
                }
            }
            log("AodHook: #1 updateRearDozeSettings(groupId=1) → alwaysOn+fullAod")
        }.onFailure { log("AodHook: #1 updateRearDozeSettings failed", it) }
    }

    // ── #2 DreamController.stopDream → block timeout kills for groupId 1 ──
    private fun hookStopDream(classLoader: ClassLoader) {
        runCatching {
            val dcClass = classLoader.loadClass("com.android.server.dreams.DreamController")
            val method = dcClass.getDeclaredMethod(
                "stopDream", Boolean::class.javaPrimitiveType!!, String::class.java)
            method.isAccessible = true
            hook(method) { chain ->
                val reason = chain.args[1] as? String
                val groupId = runCatching { chain.thisObject.getField("mGroupId") as? Int }.getOrNull()
                if (groupId == 1 && (reason == "slow to connect" || reason == "slow to finish")) {
                    log("AodHook: #2 BLOCKED stopDream('$reason') for groupId 1")
                    return@hook null
                }
                chain.proceed()
            }
            log("AodHook: #2 DreamController.stopDream guarded")
        }.onFailure { log("AodHook: #2 stopDream failed", it) }
    }

    // ── App side (SystemUI / com.miui.aod) ──────────────────────────────

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.displayAod) return
        log("AodHook(app): setupHooks pkg=${param.packageName}")
        safeHook("AodHook") { hookDreamService(param.classLoader) }
    }

    // ── #3/#4 framework DreamService (visible from SystemUI) ──
    private fun hookDreamService(classLoader: ClassLoader) {
        // #3 setDozeScreenState(int): block OFF states {0,1,3} → 4 (AOD ON); pass {2,4}.
        runCatching {
            val method = android.service.dreams.DreamService::class.java
                .getDeclaredMethod("setDozeScreenState", Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method) { chain ->
                val state = chain.args[0] as? Int ?: return@hook chain.proceed()
                when (state) {
                    0, 1, 3 -> {
                        log("AodHook: #3 setDozeScreenState($state) → 4 (AOD ON)")
                        chain.proceed(arrayOf<Any?>(4))
                    }
                    else -> chain.proceed()   // 2 (ON), 4 (AOD ON) pass through
                }
            }
            log("AodHook: #3 DreamService.setDozeScreenState hooked")
        }.onFailure { log("AodHook: #3 setDozeScreenState failed", it) }

        // #4 onDreamingStarted(): one-shot trigger for the runtime (Layer 2) hooks.
        runCatching {
            val method = android.service.dreams.DreamService::class.java
                .getDeclaredMethod("onDreamingStarted")
            method.isAccessible = true
            hook(method, after { chain, result ->
                if (!runtimeHooksInstalled) installRuntimeHooks(chain.thisObject)
                result
            })
            log("AodHook: #4 DreamService.onDreamingStarted hooked")
        }.onFailure { log("AodHook: #4 onDreamingStarted failed", it) }
    }

    // ── Layer 2: runtime hooks via the DozeMachine's own classloader ──
    private fun installRuntimeHooks(dreamService: Any?) {
        if (dreamService == null || runtimeHooksInstalled) return
        runtimeHooksInstalled = true
        runCatching {
            val machine = findObjectByClassName(dreamService, "com.miui.aod.doze.DozeMachine")
                ?: run { log("AodHook/L2: DozeMachine not found"); return }
            val machineCl = machine.javaClass.classLoader
                ?: run { log("AodHook/L2: DozeMachine classloader null"); return }
            log("AodHook/L2: found DozeMachine, cl=${machineCl.javaClass.simpleName}")

            val stateClass = machineCl.loadClass("com.miui.aod.doze.DozeMachine\$State")
            val values = stateClass.getMethod("values").invoke(null) as Array<*>
            val dozeAod = values.first { it.toString() == "DOZE_AOD" }

            // Force AOD immediately.
            runCatching { machine.callMethod("requestState", dozeAod) }
                .onFailure { log("AodHook/L2: initial requestState(DOZE_AOD) failed", it) }

            hookRequestState(machine, stateClass, dozeAod)
            hookDozeServiceSetDozeScreenState(dreamService)
            hookDozeHostIsFullAod(dreamService)
            hookFlipLinkageStyleController(machineCl)
        }.onFailure { log("AodHook/L2: installRuntimeHooks failed", it) }
    }

    // DozeMachine.requestState(): redirect DOZE/DOZE_SUSPEND/FINISH → DOZE_AOD.
    private fun hookRequestState(machine: Any, stateClass: Class<*>, dozeAod: Any?) {
        runCatching {
            val reqMethod = machine.javaClass.getDeclaredMethod("requestState", stateClass)
            reqMethod.isAccessible = true
            hook(reqMethod) { chain ->
                when (chain.args[0]?.toString()) {
                    "DOZE", "DOZE_SUSPEND", "FINISH" -> chain.proceed(arrayOf<Any?>(dozeAod))
                    else -> chain.proceed()
                }
            }
            log("AodHook/L2: DozeMachine.requestState → DOZE_AOD")
        }.onFailure { log("AodHook/L2: requestState hook failed", it) }
    }

    // DozeService.setDozeScreenState(int): same map as #3 ({0,1,3}→4).
    private fun hookDozeServiceSetDozeScreenState(dreamService: Any) {
        val dozeService = findObjectByClassName(dreamService, "com.miui.aod.doze.DozeService") ?: return
        runCatching {
            val method = dozeService.javaClass.getDeclaredMethod(
                "setDozeScreenState", Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method) { chain ->
                val s = chain.args[0] as? Int ?: return@hook chain.proceed()
                when (s) {
                    0, 1, 3 -> chain.proceed(arrayOf<Any?>(4))
                    else -> chain.proceed()
                }
            }
            log("AodHook/L2: DozeService.setDozeScreenState → 4")
        }.onFailure { log("AodHook/L2: DozeService.setDozeScreenState failed", it) }
    }

    // DozeHost.isFullAod() → false (prevent clock-container removal).
    private fun hookDozeHostIsFullAod(dreamService: Any) {
        val dozeHost = findObjectByClassName(dreamService, "com.miui.aod.DozeHost") ?: return
        runCatching {
            val method = dozeHost.javaClass.getDeclaredMethod("isFullAod")
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("AodHook/L2: DozeHost.isFullAod → false")
        }.onFailure { log("AodHook/L2: DozeHost.isFullAod failed", it) }
    }

    // FlipLinkageStyleController: isFlipped()→false, isUsingFlip()→true (kill switch).
    private fun hookFlipLinkageStyleController(machineCl: ClassLoader) {
        runCatching {
            val ctrlClass = machineCl.loadClass("com.miui.aod.flip.FlipLinkageStyleController")
            // ensure the singleton exists before hooking its instance methods
            ctrlClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
                ?: run { log("AodHook/L2: FlipLinkageStyleController.INSTANCE null"); return }
            runCatching {
                val m = ctrlClass.getDeclaredMethod("isFlipped").apply { isAccessible = true }
                hook(m, replaceResult(false))
                log("AodHook/L2: FlipLinkageStyleController.isFlipped → false")
            }.onFailure { log("AodHook/L2: isFlipped failed", it) }
            runCatching {
                val m = ctrlClass.getDeclaredMethod("isUsingFlip", android.content.Context::class.java)
                    .apply { isAccessible = true }
                hook(m, replaceResult(true))
                log("AodHook/L2: FlipLinkageStyleController.isUsingFlip → true")
            }.onFailure { log("AodHook/L2: isUsingFlip failed", it) }
        }.onFailure { log("AodHook/L2: FlipLinkageStyleController not found", it) }
    }

    // ── Object graph traversal (max depth 5, cycle-safe) ────────────────

    private fun findObjectByClassName(root: Any?, className: String): Any? =
        findRecursive(root, className, mutableSetOf(), 0)

    private fun findRecursive(obj: Any?, target: String, visited: MutableSet<Int>, depth: Int): Any? {
        if (obj == null || depth > 5) return null
        if (!visited.add(System.identityHashCode(obj))) return null
        if (obj.javaClass.name == target) return obj
        for (field in obj.javaClass.declaredFields) {
            runCatching {
                field.isAccessible = true
                val value = field.get(obj) ?: return@runCatching
                val fc = value.javaClass
                if (fc.isPrimitive || fc.name.startsWith("java.") ||
                    (fc.name.startsWith("android.") && !fc.name.contains("aod") && !fc.name.contains("doze"))
                ) return@runCatching
                findRecursive(value, target, visited, depth + 1)?.let { return it }
            }
        }
        return null
    }
}
