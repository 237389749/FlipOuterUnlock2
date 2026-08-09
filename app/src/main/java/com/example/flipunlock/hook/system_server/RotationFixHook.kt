package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 方向修复：折叠态旋转解锁（isFlipDevice→false 副作用）。
 *
 * 根因（flip2-miui-services 实机反编译，DisplayRotationStubImpl）：
 *   mUserRotationModeOuter = isFlipDevice() ? 0(FREE) : 1(LOCKED)
 *   → isFlipDevice→false 的进程（miuihome/app）折叠态旋转被锁。
 *   写设置：accelerometer_rotation = (mode != 1) ? 1 : 0  ← 0 时方向锁定
 *
 * 两条锁定路径（都要覆盖）：
 *   ① DisplayRotation.setUserRotation(int,int,String)（AOSP L619，折叠切换
 *      DoubleSwitch 走 setUserRotationWhenSwitchDisplay → 此方法）——主路径
 *   ② DisplayRotationStubImpl 私有 setUserRotation(int,int)（L261）——次路径
 *
 * 修复：两条路径 mode==1(LOCKED) → 0(FREE)，让 accelerometer_rotation 写回 1。
 *
 * 进程：system_server（flip2 注入正常——AppRestriction/AppWhitelist 已实机验证生效）
 */
object RotationFixHook {

    fun hook(param: SystemServerStartingParam) {
        log("RotationFix: setting up")
        safeHook("RotationFix") {
            // ── ① AOSP DisplayRotation.setUserRotation(int,int,String)（主路径）──
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
                log("RotationFix: ✓ hooked DisplayRotation.setUserRotation(int,int,String)")
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
        }
    }
}
