package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * DeviceState 双管齐下（2026-08-14）: 钉死 display 布局 + 改物理折叠判定返回值。
 *
 * 逻辑链(flip2 实机 XML + refMD §23):
 *   xiaomi.sensor.flip_status → device_state_configuration.xml → DeviceStateManagerService
 *     → DeviceStateCallback.onDeviceStateChanged → LogicalDisplayMapper.setDeviceStateLocked
 *       → DeviceStateToLayoutMap.get(state) → display_layout_configuration.xml
 *         → applyLayoutLocked() → enable/disable display(port 956=外屏 / 955=内屏)
 *
 * 双 hook:
 *   ① 1b: DeviceStateToLayoutMap.get(int) 恒返回目标 state 的 Layout
 *        flip2 → state 6(双屏, 外屏 default + 内屏跟随) → 任何角度/折叠态都双屏开, 外屏主屏
 *        flip1 → state 0(仅外屏, 内屏已拆) → 任何状态只外屏
 *   ② getCurrentState(system_server 服务端, 全局): 返回目标 state
 *        手电筒等 getCurrentState()==0 的折叠判定消费点看到非折叠 → 提示消失
 *        flip2 → 6(双屏); flip1 不动(恒折叠, 手电筒提示由 FlashlightHook 处理)
 *
 * 风险(已知): 状态机仍真实(回调/唤醒仍按真实折叠), 只影响"读 getCurrentState"的消费点;
 *   flip2 展开时外屏变主屏(双屏)需实测视觉/触摸路由。
 *
 * 开关: persist.flipunlock.display.state(默认 true, 风险高可关回退)。
 * 进程: system_server。
 */
object DisplayStateHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayState) {
            log("DisplayStateHook: DISABLED by persist.flipunlock.display.state")
            return
        }
        val target = 6   // 双屏展开(flip1/flip2 统一; flip1 内屏已拆 enable 空屏, 用户确认测试)
        log("DisplayStateHook: setting up (target state=$target, flip2=${isFlip2Device()})")
        safeHook("DisplayStateHook") {
            // ── ① DeviceStateToLayoutMap.get(int) → 恒返回目标 state 布局 ──
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.display.DeviceStateToLayoutMap")
                val get = cls.method("get", Int::class.javaPrimitiveType!!)
                var cached: Any? = null
                hook(get) { chain ->
                    cached ?: run {
                        // 首次用目标 state 查原 map, 缓存后恒返回(布局 map 启动时加载一次, 不变)
                        val layout = chain.proceed(arrayOf<Any?>(target))
                        cached = layout
                        log("DisplayStateHook: ✓ DeviceStateToLayoutMap.get → state=$target (layout cached)")
                        layout
                    }
                }
                log("DisplayStateHook: ✓ DeviceStateToLayoutMap.get → state=$target (恒布局)")
            }.onFailure { log("DisplayStateHook: ① DeviceStateToLayoutMap.get failed: ${it.message}") }

            // ── ② DeviceStateManagerService.getCurrentState() → 返回目标 state ──
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.devicestate.DeviceStateManagerService")
                val m = cls.method("getCurrentState")
                hook(m, replaceResult(target))
                log("DisplayStateHook: ✓ getCurrentState → $target (全局, 折叠判定消费点失效)")
            }.onFailure { log("DisplayStateHook: ② getCurrentState failed: ${it.message}") }
        }
    }
}
