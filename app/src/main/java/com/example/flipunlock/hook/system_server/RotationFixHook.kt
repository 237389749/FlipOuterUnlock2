package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 方向修复（2026-08-13 合并版）：三层路径全解。
 *
 * 根因（属性 1 → system_server isFlipDevice=false 的三重副作用）：
 *   ① DisplayRotationStubImpl.needEnableSensor() 恒 false → 方向传感器永不启用 → 转设备方向不变
 *      （大部分 app 转不动的根因；属性 4 原生 true = isFlipDevice && isDisplayFolded
 *        && mUserRotationModeOuter==0，§43.7.1）
 *   ② mUserRotationModeOuter = isFlipDevice ? 0(FREE) : 1(LOCKED) → LOCKED → accelerometer_rotation=0
 *      （折叠切换 DoubleSwitch 会把外屏锁死）
 *   ③ MiuiOrientationImpl.getOrientationMode 折叠+非flip → return -1 → 系统 UI 回退 manifest portrait
 *
 * 修复（三层）：
 *   ③ needEnableSensor() → true（传感器启用，重建属性 4 的原生 flip 行为，大部分 app 旋转恢复）
 *   ① DisplayRotation.setUserRotation(int,int,String) → LOCKED→FREE（无条件，用户接受磁贴副作用）
 *   ② DisplayRotationStubImpl.setUserRotation(int,int) → LOCKED→FREE（次路径）
 *   + MiuiOrientationImpl.getOrientationMode -1→3（外屏折叠态，系统 UI 旋转，§43.2.1）
 *
 * 依赖：全部在 system_server，回调不稳定时（§43.7.2）可能整体不生效。
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

            // ── ① AOSP DisplayRotation.setUserRotation(int,int,String)（无条件 LOCKED→FREE）──
            // 2026-08-13 用户决定: 不在乎旋转开关副作用(磁贴切锁定会失效), 无条件解锁
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.DisplayRotation")
                val method = cls.method("setUserRotation",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java)
                hook(method) { chain ->
                    val mode = chain.args[0] as? Int
                    if (mode == 1) {
                        log("RotationFix: ✓ DisplayRotation.setUserRotation LOCKED→FREE")
                        chain.proceed(arrayOf<Any?>(0, chain.args[1], chain.args[2]))
                    } else {
                        chain.proceed()
                    }
                }
                log("RotationFix: ✓ hooked DisplayRotation.setUserRotation(int,int,String) [unconditional]")
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

            // ── ④ MiuiOrientationImpl.getOrientationMode：外屏折叠态 -1→3（桌面/系统UI/portrait app 旋转）──
            // 2026-08-14 修复: displayId 反射改用 declared+setAccessible 逐层向上找。
            //   原实现用 getMethod("getDisplayContent")/getField("mDisplayId") —— 这两个 API
            //   只认 public 成员, 而 ActivityRecord.getDisplayContent()(WindowContainer 声明)
            //   与 DisplayContent.mDisplayId 都是 package-private → 每次调用抛 NoSuchMethod/NoSuchField
            //   被 runCatching 静默吞掉 → 永远不返回 3 → 桌面/系统UI/portrait app 仍锁(用户实测)。
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.MiuiOrientationImpl")
                val arCls = param.classLoader.loadClass("com.android.server.wm.ActivityRecord")
                val method = cls.method("getOrientationMode", arCls, Int::class.javaPrimitiveType!!)
                hook(method, after { chain, result ->
                    val mode = result as? Int ?: -1
                    if (mode != -1) return@after mode
                    val r = chain.args[0]
                    val displayId = displayIdOf(r)
                    if (displayId == 0) {
                        log("RotationFix: ✓ getOrientationMode -1 → 3 (FLIP_OUTSIDE, display0 外屏)")
                        3
                    } else {
                        if (displayId == null) {
                            log("RotationFix: ④ displayId 探测失败(保持 -1, 桌面/app 仍锁)")
                        }
                        mode
                    }
                })
                log("RotationFix: ✓ hooked MiuiOrientationImpl.getOrientationMode(ActivityRecord,int)")
            }.onFailure { log("RotationFix: ④ getOrientationMode failed: ${it.message}") }
        }
    }

    /** ActivityRecord → displayId。public API 优先, 失败则 declared+setAccessible 兼容 package-private
     *  (flip1 b5c1e89: getDisplayContent/getDisplayId/mDisplayId 均非 public)。 */
    private fun displayIdOf(r: Any?): Int? {
        if (r == null) return null
        // 1) public 路径: getDisplayContent() → getDisplayId()
        runCatching {
            val dc = r.javaClass.getMethod("getDisplayContent").invoke(r) ?: return null
            return dc.javaClass.getMethod("getDisplayId").invoke(dc) as? Int
        }
        // 2) declared 路径: 方法 getDisplayContent()/getDisplayId(), 字段 mDisplayId
        runCatching {
            val dc = declaredMethod(r.javaClass, "getDisplayContent")?.invoke(r) ?: return null
            declaredMethod(dc.javaClass, "getDisplayId")?.let { return it.invoke(dc) as? Int }
            declaredField(dc.javaClass, "mDisplayId")?.let { return it.get(dc) as? Int }
        }
        // 3) 直接字段: mDisplayContent → mDisplayId
        runCatching {
            val dc = declaredField(r.javaClass, "mDisplayContent")?.get(r) ?: return null
            declaredField(dc.javaClass, "mDisplayId")?.let { return it.get(dc) as? Int }
        }
        return null
    }

    private fun declaredMethod(cls: Class<*>, name: String): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        while (c != null) {
            runCatching {
                val m = c.getDeclaredMethod(name)
                m.isAccessible = true
                return m
            }
            c = c.superclass
        }
        return null
    }

    private fun declaredField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            runCatching {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f
            }
            c = c.superclass
        }
        return null
    }
}
