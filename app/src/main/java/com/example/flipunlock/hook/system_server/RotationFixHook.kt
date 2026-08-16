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
 *   ① DisplayRotation.setUserRotation(int,int,String) → 仅拦 DoubleSwitch(caller 过滤) LOCKED→FREE
 *      （2026-08-14 恢复控制中心"锁定方向"磁贴: 磁贴 freezeRotation 等其他 caller 放行）
 *   ② DisplayRotationStubImpl.setUserRotation(int,int) → LOCKED→FREE（次路径, 拦系统折叠同步写 settings）
 *   + MiuiOrientationImpl.getOrientationMode -1→3（外屏折叠态，系统 UI 旋转，§43.2.1）
 *   + WindowContainer.setOverrideOrientation PORTRAIT(1)→USER_ROTATION(12)（2026-08-14:
 *     属性1下 ActivityRecord 构造走 else 分支 setOverrideOrientation(ActivityRecord:1585) 绕过
 *     getOrientationMode → 设置/socmark 等 portrait 硬编码 app ④层覆盖不到; 复刻属性4 mode3 效果）
 *   + WindowContainer.setOrientation(int,WindowContainer) 入口 PORTRAIT(1)→USER_ROTATION(12)
 *     （2026-08-16 #22: 2参 setOrientation 内 setOverrideOrientation 后直接写 mOverrideOrientation
 *     字段(flip1:1072)绕过 ⑤ 层 → 方向重算把 portrait 写回; ⑥ 层在最终汇聚点入口拦截）
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

            // ── ① AOSP DisplayRotation.setUserRotation(int,int,String)（仅拦 DoubleSwitch 系统折叠锁）──
            // 2026-08-14 恢复磁贴: 由无条件改回 caller 过滤(8cf5d70 方案, 用户确认)。
            //   属性1下系统折叠切换 setUserRotationWhenSwitchDisplay 以 "DoubleSwitch#Outer/Inner"
            //   为 caller 发 LOCKED(外屏锁死根因 §43.7②), 只拦它;
            //   磁贴(freezeRotation)等其他 caller 放行 → 控制中心"锁定方向"磁贴恢复。
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
                        log("RotationFix: ✓ DoubleSwitch LOCKED→FREE (磁贴等其他 caller 放行)")
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

            // ── ④ MiuiOrientationImpl.getOrientationMode：外屏折叠态 -1→3（桌面/系统UI/portrait app 旋转）──
            // 2026-08-14 修复(2): 无条件 -1→3，不再卡 displayId==0 条件。
            //   依据: ① flip1 单 display 活跃(display1 内屏已拆仍枚举, 无 app 运行);
            //         ② 属性4原生行为=折叠态外屏全 app mode3(除 skip 名单), 此处即复刻;
            //         ③ 用户实测: 桌面/计算器(requestedOrientation=USER_PORTRAIT=3)走
            //            setOrientation overrideOrientationAllowed=false 分支直接 super.setOrientation,
            //            根本不经过 getOrientationMode —— 它们能转是 ①②③ 层的功劳, ④层是否生效看不出来;
            //            设置/通知栏/控制中心(硬 PORTRAIT=1)必须 ④层 -1→3 才能转, 实测仍锁
            //            → displayIdOf 探测失败或调用未覆盖是唯一解释, 无条件转换排除 displayId 因素。
            //   displayId 仅留日志用; 每次转换打 pkg 日志(日志不可靠, 出现即铁证)。
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.MiuiOrientationImpl")
                val arCls = param.classLoader.loadClass("com.android.server.wm.ActivityRecord")
                val method = cls.method("getOrientationMode", arCls, Int::class.javaPrimitiveType!!)
                hook(method, after { chain, result ->
                    val mode = result as? Int ?: -1
                    if (mode != -1) return@after mode
                    val r = chain.args[0]
                    val displayId = displayIdOf(r)
                    val pkg = runCatching {
                        r?.javaClass?.getMethod("getPackageName")?.invoke(r) as? String
                    }.getOrNull()
                    log("RotationFix: ✓ getOrientationMode -1 → 3 (FLIP_OUTSIDE) pkg=$pkg display=$displayId")
                    3
                })
                log("RotationFix: ✓ hooked MiuiOrientationImpl.getOrientationMode(ActivityRecord,int) [unconditional]")
            }.onFailure { log("RotationFix: ④ getOrientationMode failed: ${it.message}") }

            // ── ⑤ WindowContainer.setOverrideOrientation(int): 构造时 PORTRAIT→USER_ROTATION ──
            // 2026-08-14: 属性1(isFlipDevice=false)下 ActivityRecord 构造走 else 分支
            //   setOverrideOrientation(info.screenOrientation)(ActivityRecord.java:1585),
            //   绕过 setOrientation/getOrientationMode → ④ 层覆盖不到 portrait 硬编码 app
            //   (设置/socmark 实测锁: overrideOrientation=PORTRAIT)。hook 把 PORTRAIT(1)
            //   →USER_ROTATION(12), 复刻属性4下 getOrientationMode→3→case3→screenOrientation=12
            //   的效果。只改 1→12, 其他值原样(DisplayRotation 重置 -1 / TaskFragment 不受影响)。
            //   2026-08-16 补充: 本层还覆盖 ActivityRecord.getRequestedOrientation() 里
            //   DisplayRotationStub.overrideOrientationIfNeed 返回非 -2 时的 setOverrideOrientation
            //   (flip1:6413 / flip2:6546) 路径。
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.ActivityRecord")
                val method = cls.method("setOverrideOrientation", Int::class.javaPrimitiveType!!)
                hook(method) { chain ->
                    val orient = chain.args[0] as? Int
                    if (orient == 1) {
                        log("RotationFix: ✓ setOverrideOrientation PORTRAIT→USER_ROTATION(12)")
                        chain.proceed(arrayOf<Any?>(12))
                    } else {
                        chain.proceed()
                    }
                }
                log("RotationFix: ✓ hooked setOverrideOrientation(int) [PORTRAIT→12]")
            }.onFailure { log("RotationFix: ⑤ setOverrideOrientation failed: ${it.message}") }

            // ── ⑥ WindowContainer.setOrientation(int, WindowContainer): 方向重算路径的 PORTRAIT 拦截 ──
            // 2026-08-16 (#22 根因): ⑤ 层只 hook setOverrideOrientation(int), 但
            //   WindowContainer.setOrientation(2参, flip1:1058/flip2:1066) 内部
            //   setOverrideOrientation(requestedOrientation) 之后**直接写字段**
            //   mOverrideOrientation = requestedOrientation(flip1:1072/flip2:1080) —— 绕过方法!
            //   属性1 下每次方向重算(ActivityRecord.setOrientation 6297 各分支 super.setOrientation /
            //   overrideOrRestoreOrientationIfNeed 6362 super.setOrientation(origin))都把
            //   portrait(1) 直接写回 → 设置/socmark 实测仍锁(⑤ 层"未生效"真相)。
            //   hook 所有 super.setOrientation 的最终汇聚点(父类 package-private), 入口 1→12,
            //   则 1070/1072 写入的全是 12; -1(重置)/其他值原样(TaskFragment/DisplayRotation 不受影响)。
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.WindowContainer")
                val method = cls.method("setOrientation",
                    Int::class.javaPrimitiveType!!,
                    cls)
                hook(method) { chain ->
                    val orient = chain.args[0] as? Int
                    if (orient == 1) {
                        log("RotationFix: ✓ setOrientation(2参) PORTRAIT→USER_ROTATION(12)")
                        chain.proceed(arrayOf<Any?>(12, chain.args[1]))
                    } else {
                        chain.proceed()
                    }
                }
                log("RotationFix: ✓ hooked WindowContainer.setOrientation(int,WindowContainer) [PORTRAIT→12]")
            }.onFailure { log("RotationFix: ⑥ setOrientation(2参) failed: ${it.message}") }
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
