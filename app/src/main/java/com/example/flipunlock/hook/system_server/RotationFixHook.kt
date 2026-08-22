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
 *   ⑦-C(原①) DisplayRotation.setUserRotation(int,int,String) → caller 过滤:
 *      磁贴("SoSc#setRotationLock")放行(用户主动锁定保留) + 其他 caller(含 DoubleSwitch) LOCKED→FREE
 *   ② DisplayRotationStubImpl.setUserRotation(int,int) → LOCKED→FREE（次路径, 拦系统折叠同步写 settings）
 *   + MiuiOrientationImpl.getOrientationMode -1→3（外屏折叠态，系统 UI 旋转，§43.2.1）
 *   + WindowContainer.setOverrideOrientation PORTRAIT(1)→USER_ROTATION(12)（2026-08-14:
 *     属性1下 ActivityRecord 构造走 else 分支 setOverrideOrientation(ActivityRecord:1585) 绕过
 *     getOrientationMode → 设置/socmark 等 portrait 硬编码 app ④层覆盖不到; 复刻属性4 mode3 效果）
 *   + WindowContainer.setOrientation(int,WindowContainer) 入口 PORTRAIT(1)→USER_ROTATION(12)
 *     （2026-08-16 #22: 2参 setOrientation 内 setOverrideOrientation 后直接写 mOverrideOrientation
 *     字段(flip1:1072)绕过 ⑤ 层 → 方向重算把 portrait 写回; ⑥ 层在最终汇聚点入口拦截）
 *   + ⑦ 层(2026-08-20 #22 残留"同一软件有些能转有些不能转"根因实锤):
 *     DisplayRotationStubImpl.mUserRotationModeOuter = isFlipDevice?0:1 = 1(LOCKED, 构造:78)
 *       → MiuiSettingsObserver.observe/onChange → updateRotationMode() → setUserRotation(1,rot)
 *         → 写 settings accelerometer_rotation=0
 *       → DisplayRotation.updateSettings()(读 settings) → mUserRotationMode=1
 *       → updateRotationUnchecked: mUserRotationMode==1 时 orientation∈{2,-1,11,12,13}
 *         (unspecified/user/USER_ROTATION)走锁定路径 preferredRotation=mUserRotation(固定竖屏);
 *         只有显式 sensor 类 {4,6,7,10}(sensorLandscape/sensorPortrait/fullSensor 等)走传感器
 *       → 现象 = 同一软件: 显式 sensor 页面能转, 默认/user/portrait 页面锁死
 *         (⑤⑥ 层把 portrait→12 后 12 也被 mUserRotationMode==1 锁死 → "⑤⑥已实现仍部分锁"的真相)
 *     修复: ⑦-A updateRotationMode()→no-op(防系统写坏 settings)
 *           ⑦-B setUserRotationWhenSwitchUser()→no-op(防用户切换写坏)
 *           ⑦-C setUserRotation(3参) 所有 caller LOCKED→FREE(2026-08-21 起放弃磁贴,
 *               用户决策: 以放弃"锁定方向"磁贴为代价换全应用可旋转; 不再区分 SoSc 磁贴 caller)
 *           ⑦-D updateSettings() after → 无条件反射 mUserRotationMode=0(修 settings 残留 0
 *               的启动锁, 不再保留磁贴锁定状态)
 *     取舍(2026-08-21 更新): 控制中心"锁定方向"磁贴永久失效(用户确认已无法控制, 放弃);
 *           设置应用"自动旋转"开关在属性 1 下同样被强制解锁。
 *   + ⑤ 层修复(2026-08-21): 原 hook ActivityRecord.setOverrideOrientation(int), 但该方法在
 *     父类 WindowContainer 声明(ActivityRecord 未 override), 依赖 libxposed 继承链查找, 有
 *     静默失效疑点 → 设置主页(MiuiSettings, manifest portrait)构造 setOverrideOrientation(1)
 *     未转 12 仍锁的根因候选; 改为直接 hook 声明类 WindowContainer.setOverrideOrientation(int),
 *     所有调用(构造 1593 / getRequestedOrientation 6546)动态分派命中, 不依赖继承链。
 *   + ⑦-E 最终裁决层兜底(2026-08-22, 三 agent 深挖): 剩余锁根因 = ① flip2 updateSettings 是
 *     private(1102) → ⑦-D 有 miss 风险 → mUserRotationMode 残留 1; ② mSupportAutoRotation=false
 *     + ⑤⑥ 任一失效 → portrait(1)/user(3)/reversePortrait(9) 走 911→-1→937 default 保持竖屏
 *     (901 自由分支只含 {2,-1,11,12,13} 不含 1/3/9)。hook rotationForOrientation(int,int)(825,
 *     所有 app 方向的最终裁决汇聚点): 入口无条件 mUserRotationMode=0 + 固定竖屏类 {1,3,9}→12
 *     (USER_ROTATION) → 单点即可全解锁, 不依赖任何上层 hook 成功; 属性4下等价原生无副作用。
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

            // ── ⑦-C DisplayRotation.setUserRotation(int,int,String): 所有 caller LOCKED→FREE ──
            // 2026-08-14 起为 caller 过滤(磁贴放行); 2026-08-21 用户决策: 放弃磁贴,
            // 以"所有应用可旋转"为最高优先级 → 任何 caller(含磁贴 SoSc#setRotationLock、
            //   DoubleSwitch#Outer/Inner、Settings 系统路径) mode==1 一律 →FREE。
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.DisplayRotation")
                val method = cls.method("setUserRotation",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java)
                hook(method) { chain ->
                    val mode = chain.args[0] as? Int
                    if (mode == 1) {
                        log("RotationFix: ✓ setUserRotation LOCKED→FREE caller=${chain.args[2]}")
                        chain.proceed(arrayOf<Any?>(0, chain.args[1], chain.args[2]))
                    } else {
                        chain.proceed()
                    }
                }
                log("RotationFix: ✓ hooked DisplayRotation.setUserRotation(int,int,String) [全 caller LOCKED→FREE, 弃磁贴]")
            }.onFailure { log("RotationFix: ⑦-C setUserRotation failed: ${it.message}") }

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

            // ── ⑦-A DisplayRotationStubImpl.updateRotationMode() → no-op ──
            // 属性1下 mUserRotationModeOuter=1(LOCKED, 构造:78), MiuiSettingsObserver.observe(658)/
            // onChange(663) → updateRotationMode → setUserRotation(1,rot)(260) → 写 settings
            // accelerometer_rotation=0 → DisplayRotation.updateSettings 读到 1(LOCKED) → 901/908
            // 锁死所有非显式 sensor 页面。no-op 断掉这条"系统误锁"源头。
            // 属性4下 mUserRotationModeOuter=0(FREE), no-op 无副作用(settings 本就不被写坏)。
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.DisplayRotationStubImpl")
                hook(cls.method("updateRotationMode"), replaceResult(Unit))
                log("RotationFix: ✓ updateRotationMode → no-op (防系统误锁 settings)")
            }.onFailure { log("RotationFix: ⑦-A updateRotationMode failed: ${it.message}") }

            // ── ⑦-B DisplayRotationStubImpl.setUserRotationWhenSwitchUser() → no-op ──
            // 用户切换时 updateOutInnerRotationMode(把 mUserRotationModeOuter=1 同步进 settings)
            // + setUserRotationWhenSwitchDisplay("DoubleSwitch#Inner/Outer") 同样会写坏 → 一并断掉。
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.DisplayRotationStubImpl")
                hook(cls.method("setUserRotationWhenSwitchUser"), replaceResult(Unit))
                log("RotationFix: ✓ setUserRotationWhenSwitchUser → no-op")
            }.onFailure { log("RotationFix: ⑦-B setUserRotationWhenSwitchUser failed: ${it.message}") }

            // ── ⑦-D DisplayRotation.updateSettings() after → 无条件 mUserRotationMode=0 ──
            // updateSettings(1102) 从 settings 读 accelerometer_rotation(1141) → mUserRotationMode(1145)。
            // settings 若残留 0(被属性1早期写坏/历史残留) → mode=1 → 锁死。after 无条件强制 0(FREE)。
            // 2026-08-21: 去掉 userRequestedLock 判断 —— 已放弃磁贴, 不再保留任何锁定状态。
            // updateSettings 返回 boolean(shouldUpdateRotation): 字段被改时返回 true 触发 updateRotation。
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.DisplayRotation")
                hook(cls.method("updateSettings"), after { chain, result ->
                    val dr = chain.thisObject
                    val f = declaredField(dr.javaClass, "mUserRotationMode")
                    if (f != null) {
                        val cur = f.get(dr) as? Int
                        if (cur != 0) {
                            f.set(dr, 0)
                            log("RotationFix: ✓ updateSettings mUserRotationMode $cur → 0 (FREE)")
                            true
                        } else result
                    } else result
                })
                log("RotationFix: ✓ hooked DisplayRotation.updateSettings [无条件 mUserRotationMode=0]")
            }.onFailure { log("RotationFix: ⑦-D updateSettings failed: ${it.message}") }

            // ── ⑦-E DisplayRotation.rotationForOrientation(int,int): 最终裁决层兜底(2026-08-22)──
            // 三 agent 深挖结论: 前面 ⑦/④⑤⑥ 都是"改写入方", 而 display 旋转的最终裁决点是
            // rotationForOrientation(flip2:825) —— 所有 app 的方向最终都汇聚到这里计算 display 旋转。
            // 剩余锁根因: ① flip2 updateSettings 是 private(1102, flip1 public 1096) → ⑦-D 有
            //    hook miss 风险 → mUserRotationMode 残留 1 → 12 也锁(901 需 mode==0);
            //    ② mSupportAutoRotation=false(config_supportSystemNavigationKeys=false) + ⑤⑥
            //    任一失效 → portrait(1)/user(3)/reversePortrait(9) 走 911 → -1 → 937 default
            //    → return i2 保持竖屏(901 列表只含 {2,-1,11,12,13}, 不含 1/3/9)。
            // 在最终裁决点兜底(不计代价):
            //   1) 入口无条件反射 mUserRotationMode=0(FREE) —— 不依赖 updateSettings hook;
            //   2) 入口参数固定竖屏类 {1,3,9} → 12(USER_ROTATION) —— 即使 per-app 写入失败
            //      (mOverrideOrientation 仍是 1), 裁决时也当 12 处理 → mode==0 时进 901 传感器自由转。
            // 属性4下此层安全: mode 本来就是 0; 1/3/9→12 与原生 getOrientationMode→3→case3→12 等价。
            // landscape 固定类(0/5/8)保持原样(它们走 911→-1→保持横屏, 不锁竖屏)。
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.DisplayRotation")
                val method = cls.method("rotationForOrientation",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!)
                hook(method) { chain ->
                    val dr = chain.thisObject
                    // 兜底1: mUserRotationMode 强制 0(FREE)
                    runCatching {
                        val f = declaredField(dr.javaClass, "mUserRotationMode")
                        if (f != null) {
                            val cur = f.get(dr) as? Int
                            if (cur != 0) {
                                f.set(dr, 0)
                                log("RotationFix: ✓ rotationForOrientation mUserRotationMode $cur→0")
                            }
                        }
                    }
                    // 兜底2: 固定竖屏类 → USER_ROTATION(12)
                    val orient = chain.args[0] as? Int
                    if (orient == 1 || orient == 3 || orient == 9) {
                        log("RotationFix: ✓ rotationForOrientation orientation $orient→12(USER_ROTATION)")
                        chain.proceed(arrayOf<Any?>(12, chain.args[1]))
                    } else {
                        chain.proceed()
                    }
                }
                log("RotationFix: ✓ hooked DisplayRotation.rotationForOrientation [最终裁决兜底]")
            }.onFailure { log("RotationFix: ⑦-E rotationForOrientation failed: ${it.message}") }

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
            //   2026-08-21 修复: 原 hook ActivityRecord.method("setOverrideOrientation", int),
            //   但该方法在父类 WindowContainer 声明(ActivityRecord 未 override) → 依赖 libxposed
            //   继承链查找, 有静默失效疑点(设置主页构造 setOverrideOrientation(1) 未转 12 的候选
            //   根因)。改直接 hook 声明类 WindowContainer.setOverrideOrientation(int) —— 必然找到,
            //   且所有调用(构造 1593 / getRequestedOrientation 6546 / WindowContainer.setOrientation
            //   1078 内部)动态分派到 WindowContainer 实现, 全部命中。
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.WindowContainer")
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
                log("RotationFix: ✓ hooked WindowContainer.setOverrideOrientation(int) [PORTRAIT→12]")
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
