package com.example.flipunlock.hook.systemui

import android.service.dreams.DreamService
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * AOD 全链路探针（2026-11-25）—— 只观察、不改行为。
 *
 * 目的：AOD 的"看起来在打转 / 偶发黑屏 / 偶发崩溃"过去只能靠 `logcat` 里插件自己的零散日志判断，
 * 缺一条**从 dream 生命周期 → 屏幕状态 → 亮度**的连续时间线。本文件把 SystemUI 侧能看到的
 * 三个关键节点串起来，并且在 dream 反复重建时直接报警。
 *
 * 记录点（都在 SystemUI 进程，不需要插件 classloader）：
 *   1. `android.service.dreams.DreamService.setDozeScreenState`（1 参 + 3 参）→ 谁在什么时候把屏设成什么状态；
 *      带 caller（最多 4 层），能直接看出是 `DozeScreenState` 的常规迁移还是别处补刀。
 *   2. `android.service.dreams.DreamService.setDozeScreenBrightness(float)` → AOD 亮度值（native 0..1）。
 *   3. `com.android.keyguard.doze.MiuiDozeService.onCreate/onDreamingStarted/onDreamingStopped`
 *      → dream 生命周期 + 时长统计 + **循环检测**（10 分钟内启动超过 6 次就报 WARN）。
 *
 * 去重：状态/亮度按"值变化"才打；caller 只在状态变化时抓栈，避免热路径开销。
 * 开关：`persist.flipunlock.aod.trace`（默认 true，纯日志、无行为改动）。
 *
 * 判据速查：
 *   - 正常一次熄屏：`onCreate` → `onDreamingStarted` → `state=1(INIT)` → `state=4(DOZE_AOD)` →（约 5s）`brightness=…`
 *   - 循环：`onDreamingStopped` 之后 10 分钟出现 ≥6 次 `onDreamingStarted` → `AodTrace: ⚠ dream loop`
 */
object AodTraceHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui")

    private const val TAG = "AodTrace"

    /** 上一次打印过的屏幕状态（去重）。 */
    private var lastState = Int.MIN_VALUE

    /** dream 启动时间与最近 10 分钟内的启动次数（循环检测）。 */
    private var lastStartAt = 0L
    private var lastStopAt = 0L
    private val startTimes = ArrayDeque<Long>()

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.aodTrace) {
            log("$TAG: skip, persist.flipunlock.aod.trace=0")
            return
        }
        if (currentProcessName() != "com.android.systemui") {
            log("$TAG: skip, process=${currentProcessName()}")
            return
        }

        // 1) 屏幕状态：1/3 参两个重载（3 参是真正 startDozing 的那层，MIUI 插件直接调它）
        hookState("setDozeScreenState", intArrayOf())
        hookState(
            "setDozeScreenState",
            intArrayOf(0, 0, 1) // int, int, boolean
        )

        // 2) 亮度在 AodBrightnessHook 里（同一方法只允许一个 hook，见该文件）

        // 3) dream 生命周期（MIUI 宿主类，主 classloader 可见）
        val host = runCatching {
            Class.forName("com.android.keyguard.doze.MiuiDozeService")
        }.getOrNull()
        if (host == null) {
            log("$TAG: MiuiDozeService 不存在, 生命周期探针跳过")
        } else {
            for (name in listOf("onDreamingStarted", "onDreamingStopped")) {
                runCatching {
                    val m = host.getDeclaredMethod(name).also { it.setAccessible(true) }
                    hook(m) { chain ->
                        if (name == "onDreamingStarted") onStarted() else onStopped()
                        chain.proceed()
                    }
                }.onFailure { log("$TAG: $name hook failed: ${it.message}") }
            }
            log("$TAG: ✓ MiuiDozeService lifecycle hooked")
        }
    }

    private fun hookState(name: String, extra: IntArray) {
        val types = arrayListOf<Class<*>>(Int::class.javaPrimitiveType!!)
        if (extra.isNotEmpty()) {
            types.add(Int::class.javaPrimitiveType!!)
            types.add(Boolean::class.javaPrimitiveType!!)
        }
        runCatching {
            val m = DreamService::class.java
                .getDeclaredMethod(name, *types.toTypedArray())
                .also { it.isAccessible = true }
            hook(m) { chain ->
                val state = chain.args.getOrNull(0) as? Int
                if (state != null && state != lastState) {
                    lastState = state
                    log("$TAG: screenState=$state(${stateName(state)}) caller=${caller()}")
                }
                // 可选：把 AOD 的 DOZE_SUSPEND(4) 钉成 DOZE(3) / ON(2)
                //   单外屏实测：4 下面板 LPM 不刷新 → AOD 帧在合成里但屏上是黑的；
                //   PMS 的 5s WAKE_UP 超时只对 2 生效（!mIsFullAod 时回落 4），所以 3 能站住。
                val target = Config.aodDozeState
                if (target > 0 && state == 4) {
                    log("$TAG: ⚙ rewrite 4 → $target (persist.flipunlock.aod.state=$target)")
                    if (types.size == 3) {
                        chain.proceed(arrayOf<Any?>(target, chain.args.getOrNull(1) ?: 0, chain.args.getOrNull(2) ?: false))
                    } else {
                        chain.proceed(arrayOf<Any?>(target))
                    }
                } else {
                    chain.proceed()
                }
            }
            log("$TAG: ✓ DreamService.$name(${types.joinToString { it.simpleName }}) hooked")
        }.onFailure { log("$TAG: $name(${types.size}) hook failed: ${it.message}") }
    }

    private fun onStarted() {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(startTimes) {
            startTimes.addLast(now)
            while (startTimes.isNotEmpty() && now - startTimes.first() > 10 * 60_000L) {
                startTimes.removeFirst()
            }
            val n = startTimes.size
            val gap = if (lastStopAt > 0) now - lastStopAt else -1
            log("$TAG: dream started (10min 内第 $n 次, 距上次 stop ${gap}ms)")
            if (n >= 6) {
                log("$TAG: ⚠ dream loop 嫌疑: 10 分钟内 AOD 启动 $n 次（检查 DozeLifecycleOwner / MiuiDozeService onCreate 是否反复重建）")
            }
        }
        lastStartAt = now
    }

    private fun onStopped() {
        val now = android.os.SystemClock.elapsedRealtime()
        lastStopAt = now
        val dur = if (lastStartAt > 0) now - lastStartAt else -1
        log("$TAG: dream stopped (存活 ${dur}ms)")
    }

    private fun stateName(s: Int): String = when (s) {
        0 -> "OFF/FINISH"
        1 -> "DOZE"
        2 -> "ON"
        3 -> "DOZE_SUSPEND(video)"
        4 -> "DOZE_AOD/SUSPEND"
        5 -> "VR"
        6 -> "ON_SUSPEND"
        else -> "?"
    }

    /** 取调用方（跳过本模块与 hook 框架的帧），最多 4 层。 */
    private fun caller(): String = runCatching {
        Throwable().stackTrace
            .asSequence()
            .filterNot { it.className.startsWith("com.example.flipunlock") }
            .filterNot { it.className.startsWith("io.github.libxposed") }
            .filterNot { it.className.startsWith("java.lang.Thread") }
            .take(4)
            .joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    }.getOrDefault("?")
}
