package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * AOD "全屏 AOD" 标志钉住（2026-11-25）—— 让外屏在 AOD 期间保持 ON（连续扫描）。
 *
 * ## 为什么需要它（refMD `AOD_Full_Chain.md` §9，kernel log 实证）
 * 单外屏机器上 AOD 帧**只存在于合成层**：
 * ```
 * [mi_disp] [primary] feature: layer_has_aod, value: 1     ← AOD 图层标志只发给了 primary
 * [mi_disp] [W][primary] panel not initialized!            ← 内屏已拆，没人接
 * [mi_disp] Display (secondary), Power mode (Doze)         ← 外屏只收到 doze
 * [mi_disp] secondary panel: DSI_CMD_SET_LP1               ← 然后进 LP1（面板自刷新旧帧 = 黑）
 * ```
 * 所以"投屏/截图有 AOD、屏上没有"：外屏进了低功耗自刷新，显示的是进 doze 前那一帧。
 *
 * ## 为什么不能只改 doze 状态
 * 唯一能让外屏真正扫描 AOD 的是 `screenState = 2 (ON)`；但 PMS 在
 * `updateDisplayPowerStateLocked` 里判断
 * `screenState==2 && (!mAlwaysOnEnabled || !mIsFullAod)` → 当成"提前点亮"，
 * 排 **WAKE_UP_TIMEOUT_MILLIS = 5000ms** 后回落 4（`PowerManagerService.java:4740/4747`）。
 * 这也是实测"抬到 ON 约 5 秒后又变 4"的真正原因（不是 AOD 插件的定时器）。
 *
 * `mIsFullAod` 由 MIUI 的 `PowerManagerService.updateFullAodModeLocked(boolean)`（:4621）写入；
 * 它的来源是 `MiuiFullAodManager.fullAodEnable()`，而 bixi.xml **没有** `support_aod_fullscreen`
 * → 插件 `SUPPORT_FULL_AOD=false` → 恒 false。
 *
 * ## 做法
 * 把 `updateFullAodModeLocked(boolean)` 的入参钉成 `true`，PMS 就认为"这是合法的全屏 AOD 状态"，
 * 不再 5 秒回落；配合 `persist.flipunlock.aod.state=2`（AodTraceHook 把梦的 doze 状态抬成 ON），
 * 外屏保持 ON、连续扫描 AOD 帧。
 *
 * ## 开关与边界
 * - 只在 `persist.flipunlock.aod.state == 2` 且 `persist.flipunlock.display.aod=1` 时安装；
 * - 不改变 AOD 内容/样式（只用电源策略标志，不碰 `MiuiFullAodManager` 的渲染路径）；
 * - 副作用：AOD 期间面板是**真 ON**（不是 LDPM），功耗高于原生 doze；这是"看得见"的代价。
 */
object AodFullAodPinHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayAod) {
            log("AodFullAodPin: skip, persist.flipunlock.display.aod=0")
            return
        }
        val target = Config.aodDozeState
        if (target != 2) {
            log("AodFullAodPin: 当前 aod.state=$target（不是 2），本次 boot 只装 hook 不生效；设 2 后自动起效")
        }
        safeHook("AodFullAodPin") {
            val cls = param.classLoader.loadClass("com.android.server.power.PowerManagerService")
            val method = cls.method("updateFullAodModeLocked", Boolean::class.javaPrimitiveType!!)
            var logged = false
            hook(method) { chain ->
                // 运行时判定：只有 aod.state==2 才钉 true（改 prop 不必重启）
                if (Config.aodDozeState != 2) return@hook chain.proceed()
                val orig = chain.args.getOrNull(0)
                if (!logged) {
                    logged = true
                    log("AodFullAodPin: updateFullAodModeLocked($orig) → true（解除 5s WAKE_UP 回落）")
                }
                chain.proceed(arrayOf<Any?>(true))
            }
            log("AodFullAodPin: ✓ PowerManagerService.updateFullAodModeLocked hooked (state==2 时钉 true)")
        }
    }
}
