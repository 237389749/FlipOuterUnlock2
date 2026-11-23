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
 *   Layer 0 (framework, visible from SystemUI — runs FIRST, fixes a hard crash):
 *     #5 android.view.Display.getCutout() → DisplayCutout.NONE. The AOD flip path
 *     DozeHost.dealWithFlipChange()→DisplayUtils.getCutoutPosition() dereferences
 *     display.getCutout() with no null check; our CutoutRemove zeroes it → NPE →
 *     SystemUI crash-loop at plugin connect (before any dream). The old project dodged
 *     this only because its DeviceIdentityHook made isFlipDevice()→false and skipped the
 *     flip path entirely. Returning the empty non-null NONE defuses the NPE.
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
 * ── 两线分工（2026-11-23，v3.4）──
 * unlock2 = 属性 1：AOD 只维护 **flip1** 路径（flip2 在本文件入口整体 SKIP，flip2 的 AOD 归 NoProp）。
 * 本次只移植「与机型无关」的修复：① L2 改成「装齐后置位」（失败可重试）
 * ② setDozeScreenState：state 0 放行 + AOD 关闭时整体放行（不再把熄屏抬成 ON）
 * ③ DozeLifecycleOwner.initState 防崩（DESTROYED → INITIALIZED）。
 * **不移植** flip2 专属的 fullAodEnable→false / FlipLinkage 切断 / state 4 原生放行 —— 那些
 * 只在属性 4 下成立，互抄会把 flip1 弄坏（这正是 11-23 定下的"两线不再互抄"）。
 *
 * Toggle: persist.flipunlock.display.aod (default true)
 */
object AodHook : BaseHook() {

    // "android" is required because LSPosed v2.0.1 only fires onPackageReady("android")
    // in the systemui process — onPackageReady("com.android.systemui") is never called
    // despite the package being in scope. The process guard in setupHooks() ensures the
    // app-side hooks only install in systemui / miui.aod, not system_server or other apps.
    override val targetPackages = listOf("android", "com.android.systemui", "com.miui.aod")

    /** Runtime (Layer 2) hooks installed at most once per process. */
    @Volatile
    private var runtimeHooksInstalled = false

    /** DozeLifecycleOwner.initState 防崩已装的 classloader（幂等；同一 loader 只装一次）。 */
    private val initStateHookedCls = mutableSetOf<ClassLoader>()

    /** AOD 关闭 gate 只打印一次。 */
    @Volatile
    private var aodOffGateLogged = false

    // ── Framework side (system_server) ──────────────────────────────────

    fun hookFramework(param: SystemServerStartingParam) {
        // 2026-08-14 机型 gate: flip2 AOD 正常显示, 不需要本 hook(OS3.1 AOD 逻辑不同)
        if (isFlip2Device()) {
            log("AodHook(framework): SKIP (flip2 AOD 正常)")
            return
        }
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
        // 2026-08-14 机型 gate: flip2 AOD 正常显示, 不需要本 hook
        if (isFlip2Device()) {
            log("AodHook(app): SKIP (flip2 AOD 正常)")
            return
        }
        // When pkg="android", only proceed if we're actually inside systemui / miui.aod.
        // This avoids installing app-side hooks in system_server or unrelated app processes.
        if (param.packageName == "android") {
            val proc = currentProcessName()
            if (proc != "com.android.systemui" && proc != "com.miui.aod") {
                log("AodHook(app): skip, process=$proc")
                return
            }
            log("AodHook(app): pkg=android but process=$proc — installing app hooks")
        } else {
            log("AodHook(app): setupHooks pkg=${param.packageName}")
        }
        safeHook("AodHook") {
            hookDisplayGetCutout(param.classLoader)
            hookMiuiDozeServiceOnCreate(param.classLoader)
            hookDreamService(param.classLoader)
        }
    }

    // ── MiuiDozeService.onCreate → initState 防崩（flip1 崩溃环的根治点）──
    // 崩溃点在 onCreate → DozeService.create → assembleMachine → initState，
    // 早于 onDreamingStarted(L2)，所以必须在这里装。此刻 plugin 已挂到实例
    // （setDozeRequester 就在 onCreate 内），从实例图能拿到 MIUIAod 的 classloader。
    private fun hookMiuiDozeServiceOnCreate(fallback: ClassLoader) {
        runCatching {
            val cl = processClassLoader(fallback)
            val cls = cl.loadClass("com.android.keyguard.doze.MiuiDozeService")
            val m = cls.getDeclaredMethod("onCreate").apply { isAccessible = true }
            hook(m, after { chain, result ->
                installInitStateDefense(cl)
                pluginClassLoaderFrom(chain.thisObject)?.let { installInitStateDefense(it) }
                result
            })
            log("AodHook: ✓ MiuiDozeService.onCreate hooked (initState 防崩)")
        }.onFailure { log("AodHook: MiuiDozeService.onCreate hook failed: ${it.message}") }
    }

    private fun currentProcessName(): String? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()

    // ── #5 android.view.Display.getCutout() → DisplayCutout.NONE (NPE fix) ──
    //
    // Crash observed on device (SystemUI crash-loop, ~7s restart):
    //   NullPointerException: DisplayCutout.getBoundingRectLeft() on a null object
    //     at com.miui.aod.util.DisplayUtils.getCutoutPosition(DisplayUtils.java)
    //     at com.miui.aod.DozeHost.dealWithFlipChange(DozeHost.java)   ← flip-only path
    //     at com.miui.aod.DozeHost.create(DozeHost.java)
    //
    // DisplayUtils.getCutoutPosition() does `display.getCutout().getBoundingRectLeft()`
    // with no null check. Our CutoutRemove zeroes the cutout, so Display.getCutout()
    // returns null → NPE. This fires the moment the AOD plugin connects (DozeHost.create),
    // i.e. BEFORE any dream starts — so the Layer-2 runtime hooks are too late to help.
    //
    // Why the OLD project never hit this: its DeviceIdentityHook forced Utils.isFlipDevice()
    // → false, so DozeHost.dealWithFlipChange() (gated on isFlipDevice) was skipped entirely
    // and getCutoutPosition was never called — i.e. the old AOD worked by pretending to be a
    // non-flip ("inner-screen") device. We don't spoof identity, so we must defuse the NPE.
    //
    // Display.getCutout() is a PUBLIC framework method (R8-safe) visible from the SystemUI
    // classloader, so hooking it here needs no plugin classloader and no timing tricks.
    // Returning the non-null empty DisplayCutout.NONE makes getCutoutPosition() fall through
    // to Direction.CAMERA_CUTOUT_ON_NONE, exactly as if there were simply no cutout.
    private fun hookDisplayGetCutout(classLoader: ClassLoader) {
        runCatching {
            val emptyCutout = emptyDisplayCutout()
                ?: run { log("AodHook: #5 no empty DisplayCutout available"); return }
            val method = android.view.Display::class.java.getMethod("getCutout")
            hook(method) { chain ->
                // Only return empty cutout when called from AOD code path.
                // A global replace breaks status bar layout, notification layout,
                // and camera app (all rely on real cutout data).
                val stack = android.util.Log.getStackTraceString(Throwable())
                if (stack.contains("com.miui.aod")) {
                    emptyCutout
                } else {
                    chain.proceed()
                }
            }
            log("AodHook: #5 Display.getCutout → empty only in AOD call path (NPE fix)")
        }.onFailure { log("AodHook: #5 Display.getCutout failed", it) }
    }

    /**
     * A non-null DisplayCutout with empty bounding rects.
     * Tries multiple strategies since the available constructors/fields vary by ROM:
     *  1. Static field NONE (API 34+ public, may exist as hidden on earlier)
     *  2. Public 7-param ctor (API 34+): (Insets, Rect, Rect, Rect, Rect, Bounds, Insets)
     *  3. Internal 4-int ctor (older): (int, int, int, int)
     *  4. Any declared ctor with all-null/zero args (last resort)
     */
    private fun emptyDisplayCutout(): android.view.DisplayCutout? {
        val clz = android.view.DisplayCutout::class.java
        // Strategy 1: static NONE field
        runCatching {
            val f = clz.getDeclaredField("NONE")
            f.isAccessible = true
            (f.get(null) as? android.view.DisplayCutout)?.let {
                log("AodHook: #5 got DisplayCutout.NONE field")
                return it
            }
        }
        // Strategy 2: public 7-param ctor (Insets, Rect*4, Bounds, Insets) — pass nulls
        runCatching {
            val insetsClz = Class.forName("android.graphics.Insets")
            val boundsClz = Class.forName("android.graphics.Rect\$Bounds")
            val ctor = clz.getDeclaredConstructor(
                insetsClz, android.graphics.Rect::class.java, android.graphics.Rect::class.java,
                android.graphics.Rect::class.java, android.graphics.Rect::class.java,
                boundsClz, insetsClz)
            ctor.isAccessible = true
            val r = android.graphics.Rect(0, 0, 0, 0)
            return ctor.newInstance(null, r, r, r, r, null, null) as android.view.DisplayCutout
        }
        // Strategy 3: 4-int ctor
        runCatching {
            val ctor = clz.getDeclaredConstructor(
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            ctor.isAccessible = true
            log("AodHook: #5 got DisplayCutout via 4-int ctor")
            return ctor.newInstance(0, 0, 0, 0) as android.view.DisplayCutout
        }
        // Strategy 4: enumerate all ctors, try first one with null/defaults
        runCatching {
            for (ctor in clz.declaredConstructors) {
                runCatching {
                    ctor.isAccessible = true
                    val params = ctor.parameterTypes.map { p ->
                        when {
                            p == Int::class.javaPrimitiveType!! -> 0
                            p == Boolean::class.javaPrimitiveType!! -> false
                            p == Long::class.javaPrimitiveType!! -> 0L
                            else -> null
                        }
                    }.toTypedArray()
                    val obj = ctor.newInstance(*params)
                    if (obj is android.view.DisplayCutout) {
                        log("AodHook: #5 got DisplayCutout via ${ctor.parameterCount}-param ctor")
                        return obj
                    }
                }
            }
        }
        log("AodHook: #5 all DisplayCutout creation strategies failed; ctors=${clz.declaredConstructors.size}")
        return null
    }

    // ── #3/#4 framework DreamService (visible from SystemUI) ──
    private fun hookDreamService(classLoader: ClassLoader) {
        // #3 setDozeScreenState(int): 全状态强制 2(ON 亮屏) —— FlipOuterUnlock 最旧版方案
        // 旧版注释: DozeScreenState 有 6s mResetScreenTask 超时, INITIALIZED→DOZE_AOD 后调
        // setDozeScreenState(1); 阻塞所有 OFF 状态(0,1,3)并强制 2(ON); 4(AOD ON)也改 2,
        // 避免复位超时振荡。AOD 内容渲染在"亮屏 ON"状态 → 物理屏必亮(flip1 实测 4 方案不亮)。
        // 值: 0=FINISH 1=DOZE 2=ON/PULSING 3=DOZE_SUSPEND 4=DOZE_AOD
        runCatching {
            val method = android.service.dreams.DreamService::class.java
                .getDeclaredMethod("setDozeScreenState", Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method) { chain ->
                val state = chain.args[0] as? Int ?: return@hook chain.proceed()
                if (passThroughWhenAodOff(state, "#3 setDozeScreenState")) return@hook chain.proceed()
                when (state) {
                    // 0 = FINISH：必须放行。强制→2 会让屏幕停在 ON、但 dream 已死且 wakefulness
                    // 已是 Dozing → 触摸 surface 被移除 = "亮着但没反应"（用户实测复现）。
                    0 -> chain.proceed()
                    1, 3, 4 -> {
                        log("AodHook: #3 setDozeScreenState($state) → 2 (ON 亮屏, 旧版方案)")
                        chain.proceed(arrayOf<Any?>(2))
                    }
                    else -> chain.proceed()   // 2 (ON) pass through
                }
            }
            log("AodHook: #3 DreamService.setDozeScreenState hooked [旧版: →2 亮屏]")
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
        // 2026-11-23: 由「入口置位」改成「装齐后置位」——之前一次失败就永久放弃，
        // 而 L2 恰恰是最容易在首轮 dream 里拿不到 DozeMachine/loader 的一层。
        val ok = runCatching {
            val machine = findObjectByClassName(dreamService, "com.miui.aod.doze.DozeMachine")
                ?: return@runCatching false
            val machineCl = machine.javaClass.classLoader
                ?: return@runCatching false
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
            installInitStateDefense(machineCl)
            true
        }.getOrDefault(false)
        if (ok) {
            runtimeHooksInstalled = true
            log("AodHook/L2: runtime hooks installed")
        } else {
            log("AodHook/L2: install incomplete -> retry on next dreaming start")
        }
    }

    // DozeMachine.requestState(): redirect DOZE/DOZE_SUSPEND/FINISH → DOZE_AOD.
    private fun hookRequestState(machine: Any, stateClass: Class<*>, dozeAod: Any?) {
        runCatching {
            val reqMethod = machine.javaClass.getDeclaredMethod("requestState", stateClass)
            reqMethod.isAccessible = true
            hook(reqMethod) { chain ->
                // 2026-11-23: FINISH 不再被改写。拦 FINISH 会让 dream 无法结束（状态机与
                // wakefulness 不一致），是崩溃环/“AOD 退不出去”的疑点；DOZE/DOZE_SUSPEND 仍抬到 AOD。
                when (chain.args[0]?.toString()) {
                    "DOZE", "DOZE_SUSPEND" -> chain.proceed(arrayOf<Any?>(dozeAod))
                    else -> chain.proceed()
                }
            }
            log("AodHook/L2: DozeMachine.requestState → DOZE_AOD")
        }.onFailure { log("AodHook/L2: requestState hook failed", it) }
    }

    // DozeService.setDozeScreenState(int): 同 #3 —— 全状态强制 2(ON 亮屏, 旧版方案).
    private fun hookDozeServiceSetDozeScreenState(dreamService: Any) {
        val dozeService = findObjectByClassName(dreamService, "com.miui.aod.doze.DozeService") ?: return
        runCatching {
            val method = dozeService.javaClass.getDeclaredMethod(
                "setDozeScreenState", Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method) { chain ->
                val s = chain.args[0] as? Int ?: return@hook chain.proceed()
                if (passThroughWhenAodOff(s, "L2 DozeService.setDozeScreenState")) return@hook chain.proceed()
                when (s) {
                    0 -> chain.proceed()             // FINISH 放行（同 #3）
                    1, 3, 4 -> chain.proceed(arrayOf<Any?>(2))
                    else -> chain.proceed()
                }
            }
            log("AodHook/L2: DozeService.setDozeScreenState → 2 (旧版方案)")
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

    // ── 公共 helper：Context / AOD 开关 / plugin classloader / initState 防崩 ──

    private fun systemContext(): android.content.Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        (at.getMethod("currentApplication").invoke(null) as? android.content.Context)
            ?: (at.getMethod("getSystemContext").invoke(null) as? android.content.Context)
    }.getOrNull()

    private fun aodEnabledBySetting(): Boolean = runCatching {
        val ctx = systemContext() ?: return@runCatching true
        android.provider.Settings.Secure.getInt(ctx.contentResolver, "doze_always_on", 0) == 1
    }.getOrDefault(true)

    /**
     * true = 系统设置里 AOD 已关闭 → 调用方应**原样放行**屏幕状态，不要抬成 2(ON)。
     *
     * 为什么必须运行时判定（不能安装期早退）：AOD 开关切换后不重启 SystemUI 也要生效。
     * 不判的后果（用户实测）：关闭 AOD 后，GXZW 息屏指纹提示触发的 doze 被抬成 2(ON) →
     * 屏幕亮起 + 背景全黑 + 只剩指纹图标。
     */
    private fun passThroughWhenAodOff(state: Int, tag: String): Boolean {
        if (aodEnabledBySetting()) return false
        if (!aodOffGateLogged) {
            aodOffGateLogged = true
            log("AodHook: AOD 已关闭 (doze_always_on != 1) → $tag($state) 放行, 不抬屏")
        }
        return true
    }

    /** 从对象图反查 MIUIAod plugin 的 ClassLoader（BFS，深度 ≤6，只沿 miui.aod/keyguard 分支）。 */
    private fun pluginClassLoaderFrom(root: Any?): ClassLoader? {
        if (root == null) return null
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Any>()
        queue.add(root)
        var level = 1
        var depth = 0
        while (queue.isNotEmpty() && depth <= 6) {
            var next = 0
            repeat(level) {
                if (queue.isEmpty()) return@repeat
                val obj = queue.removeFirst()
                if (!visited.add(System.identityHashCode(obj))) return@repeat
                val cls = obj.javaClass
                if (cls.name.startsWith("com.miui.aod")) return cls.classLoader
                for (f in cls.declaredFields) {
                    val v = runCatching {
                        f.isAccessible = true
                        f.get(obj)
                    }.getOrNull() ?: continue
                    val n = v.javaClass.name
                    if (n.startsWith("com.miui.aod") || n.startsWith("com.android.keyguard")) {
                        queue.add(v)
                        next++
                    }
                }
            }
            depth++
            level = next
        }
        return null
    }

    /** 遍历候选 classloader 补装 initState 防崩（幂等）。 */
    private fun installInitStateDefense(fallback: ClassLoader) {
        val candidates = listOfNotNull(
            runCatching { processClassLoader(fallback) }.getOrNull(),
            fallback,
        ).distinct()
        for (c in candidates) {
            runCatching { c.loadClass("com.miui.aod.DozeLifecycleOwner") }.onSuccess {
                hookDozeLifecycleOwnerInitState(c)
            }
        }
    }

    /**
     * DozeLifecycleOwner.initState 防崩（flip1 实机崩溃环，见 NoProp 同款修复）。
     *
     * 现象：`IllegalStateException: no event up from DESTROYED` → SystemUI 崩 → 重启 → 复现。
     * 根因：LifecycleRegistry 是 static 单例，dream FINISH(ON_DESTROY) 后停在 DESTROYED，
     * 同进程再次 create(dream 重建) 时 forwardPass 无法从 DESTROYED 前进。
     * 修法：initState 执行前，若 registry.mState == DESTROYED → 反射直写 INITIALIZED。
     * 触发前提是本 hook 自己让 dream 在同一进程内结束后重建（#3 抬屏 / L2 强 DOZE_AOD）。
     */
    private fun hookDozeLifecycleOwnerInitState(cl: ClassLoader) {
        if (!initStateHookedCls.add(cl)) return
        runCatching {
            val ownerCls = cl.loadClass("com.miui.aod.DozeLifecycleOwner")
            val registryCls = cl.loadClass("androidx.lifecycle.LifecycleRegistry")
            val stateCls = cl.loadClass("androidx.lifecycle.Lifecycle\$State")
            val destroyed = stateCls.getField("DESTROYED").get(null)
            val initialized = stateCls.getField("INITIALIZED").get(null)
            val m = ownerCls.getDeclaredMethod("initState").apply { isAccessible = true }
            hook(m, before { chain ->
                runCatching {
                    val owner = chain.thisObject
                    val regField = ownerCls.getDeclaredField("mLifecycleRegistry").apply { isAccessible = true }
                    val registry = regField.get(owner)
                    val mStateField = registryCls.getDeclaredField("mState").apply { isAccessible = true }
                    if (mStateField.get(registry) == destroyed) {
                        mStateField.set(registry, initialized)
                        log("AodHook: ✓ DozeLifecycleOwner registry DESTROYED→INITIALIZED (initState 防崩)")
                    }
                }.onFailure { log("AodHook: initState registry reset failed: ${it.message}") }
                chain.proceed()
            })
            log("AodHook: ✓ DozeLifecycleOwner.initState 防崩 hook (cl=$cl)")
        }.onFailure { log("AodHook: initState hook failed: ${it.message}") }
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
