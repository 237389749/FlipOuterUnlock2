package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 方向修复（2026-08-13 合并版）：三层路径全解。
 *
 * 根因（属性 1 → system_server isFlipDevice=false 的三重副作用）：
 *   ① DisplayRotationStubImpl.needEnableSensor() 恒 false → 方向传感器永不启用 → 转设备方向不变
 *      （大部分 app 转不动的根因）
 *   ② mUserRotationModeOuter = isFlipDevice ? 0(FREE) : 1(LOCKED) → LOCKED → accelerometer_rotation=0
 *      （折叠切换 DoubleSwitch 会把外屏锁死）
 *   ③ MiuiOrientationImpl.getOrientationMode 折叠+非flip → return -1 → 系统 UI 回退 manifest portrait
 *
 * 修复（三层）：
 *   ③ needEnableSensor() → true（传感器启用，大部分 app 旋转恢复）
 *   ① DisplayRotation.setUserRotation(int,int,String) → 仅 caller=DoubleSwitch（折叠切换）LOCKED→FREE
 *      （其他调用如磁贴/手动锁定正常 proceed，避免磁贴切锁定失效）
 *   ② DisplayRotationStubImpl.setUserRotation(int,int) → LOCKED→FREE（次路径）
 *   + MiuiOrientationImpl.getOrientationMode -1→3（外屏折叠态，系统 UI 旋转，§43.2.1）
 *
 * 进程：system_server。
 */
object RotationFixHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.rotationFix) {
            log("RotationFix: DISABLED by persist.flipunlock.rotation.fix")
            return
        }
        log("RotationFix: setting up")
        safeHook("RotationFix") {
            // ── ③ needEnableSensor() → true（转不动的根因）──
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.DisplayRotationStubImpl")
                hook(cls.method("needEnableSensor"), replaceResult(true))
                log("RotationFix: ✓ needEnableSensor → true (sensor rotation enabled)")
            }.onFailure { log("RotationFix: ③ needEnableSensor failed: ${it.message}") }

            // ── ① AOSP DisplayRotation.setUserRotation(int,int,String)（DoubleSwitch 过滤）──
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.DisplayRotation")
                val method = cls.method("setUserRotation",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java)
                hook(method) { chain ->
                    val mode = chain.args[0] as? Int
                    val caller = chain.args[2] as? String
                    if (mode == 1 && caller != null && caller.contains("DoubleSwitch")) {
                        log("RotationFix: ✓ DoubleSwitch LOCKED→FREE")
                        chain.proceed(arrayOf<Any?>(0, chain.args[1], chain.args[2]))
                    } else {
                        chain.proceed()
                    }
                }
                log("RotationFix: ✓ hooked DisplayRotation.setUserRotation(int,int,String) [DoubleSwitch only]")
            }.onFailure { log("RotationFix: ① DisplayRotation.setUserRotation failed: ${it.message}") }

            // ── ② DisplayRotationStubImpl 私有 setUserRotation(int,int)（次路径）──
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.DisplayRotationStubImpl")
                val method = cls.method("setUserRotation",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!)
                hook(method) { chain ->
                    val mode = chain.args[0] as? Int
                    if (mode == 1) {
                        log("RotationFix: ✓ StubImpl.setUserRotation LOCKED→FREE")
                        chain.proceed(arrayOf<Any?>(0, chain.args[1]))
                    } else {
                        chain.proceed()
                    }
                }
                log("RotationFix: ✓ hooked DisplayRotationStubImpl.setUserRotation(int,int)")
            }.onFailure { log("RotationFix: ② StubImpl.setUserRotation failed: ${it.message}") }

            // ── ④ MiuiOrientationImpl.getOrientationMode：外屏折叠态 -1→3（系统 UI 旋转）──
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.MiuiOrientationImpl")
                val arCls = param.classLoader.loadClass("com.android.server.wm.ActivityRecord")
                val method = cls.method("getOrientationMode", arCls, Int::class.javaPrimitiveType!!)
                hook(method, after { chain, result ->
                    val mode = result as? Int ?: -1
                    if (mode != -1) return@after mode
                    val r = chain.args[0]
                    val displayId = runCatching {
                        val dc = r?.javaClass?.getMethod("getDisplayContent")?.invoke(r)
                        dc?.javaClass?.getField("mDisplayId")?.get(dc) as? Int
                    }.getOrNull()
                    if (displayId == 0) {
                        log("RotationFix: ✓ getOrientationMode -1 → 3 (FLIP_OUTSIDE, display0 外屏)")
                        3
                    } else {
                        mode
                    }
                })
                log("RotationFix: ✓ hooked MiuiOrientationImpl.getOrientationMode(ActivityRecord,int)")
            }.onFailure { log("RotationFix: ④ getOrientationMode failed: ${it.message}") }
        }
    }
}
