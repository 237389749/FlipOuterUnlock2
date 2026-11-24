package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * DeviceState 布局按状态分支（2026-08-14 终版）: 除全展开(state 3)外, 外屏都亮。
 *
 * 用户实测演进:
 *   初版(恒 state 6 双屏外屏主导, d284c51/c431035) → 双屏同显实现, 但"以外屏为主屏"体验不适合。
 *   终版: DeviceStateToLayoutMap.get(state) 按 state 分支:
 *     state == 3 (OPENED 全展开) → 原生布局(内屏亮)
 *     其他 state (0 折叠/1 帐篷/2 半开/4 反展...) → 恒 state 0 布局(外屏亮, 内屏 off)
 *   → 半展开/半折叠/折叠时外屏都能亮; 只有全展开才切内屏。
 *
 * getCurrentState **不 hook**: 状态保持真实(sensor 驱动), 手电筒等折叠判定
 *   消费点由 FlashlightHook 方法级拦截处理; 避免"系统认为双屏"的副作用。
 *   2026-11-23 (v3.5): 原有一版 `getCurrentState → 6` 的全局 hack 已删除 —— 它让所有
 *   消费者(方向/continuity/SystemUI)看到"双屏", 而同文件的布局钩子又把布局钉成外屏,
 *   同一进程里两套判定并存。手电筒提示改由 FlashlightHook 精确拦截。
 *
 * 开关: persist.flipunlock.display.state(默认 true)。
 * 进程: system_server。
 */
object DisplayStateHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayState) {
            log("DisplayStateHook: DISABLED by persist.flipunlock.display.state")
            return
        }
        log("DisplayStateHook: setting up (除全展开外屏亮)")
        safeHook("DisplayStateHook") {
            // ── DeviceStateToLayoutMap.get(state) → state 3 原生, 其他恒外屏(state 0 布局) ──
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.display.DeviceStateToLayoutMap")
                val get = cls.method("get", Int::class.javaPrimitiveType!!)
                var cachedOuter: Any? = null
                hook(get) { chain ->
                    val state = chain.args[0] as? Int ?: return@hook chain.proceed()
                    if (state == 3) {
                        // OPENED 全展开: 原生布局(内屏亮)
                        chain.proceed()
                    } else {
                        // 其他状态(折叠/帐篷/半开/反展): 恒 state 0 布局(外屏亮, 内屏 off)
                        cachedOuter ?: run {
                            val layout = chain.proceed(arrayOf<Any?>(0))
                            cachedOuter = layout
                            log("DisplayStateHook: ✓ 非展开状态(state=$state) → 外屏布局(state 0)")
                            layout
                        }
                    }
                }
                log("DisplayStateHook: ✓ DeviceStateToLayoutMap.get 按 state 分支(3→原生, 其他→外屏)")
            }.onFailure { log("DisplayStateHook: ① DeviceStateToLayoutMap.get failed: ${it.message}") }

            // ② `getCurrentState → 6` 已在 v3.5 删除（见文件头）。不要再加回来：
            //    它会让方向/continuity/SystemUI 把设备当"双屏"，和 ① 的外屏布局判定打架。
        }
    }
}
