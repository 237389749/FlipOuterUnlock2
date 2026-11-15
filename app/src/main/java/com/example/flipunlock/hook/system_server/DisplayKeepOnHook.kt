package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.safeHook
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Field

/**
 * 单外屏（拔掉内屏）保命 hook：阻止框架把 display 0（幽灵内屏）掉电。
 *
 * 背景（refMD `Inner_Screen_Bootloop_TEE_Watchdog.md` §8–§11）：
 *   拔掉内屏后 bootloader 仍按 `msm_drm.dsi_display0=…nt37801…` 给内核一个默认面板，
 *   display 0 是一个 1440×3200 的幽灵屏；框架一旦对它做 disable/熄屏/状态切换，
 *   TEE 侧 si_core 的 ADCI 调用永久阻塞 → Gunyah 看门狗 → 63~66s 硬复位。
 *
 * 做法：hook `DisplayPowerController#animateScreenStateChange`，
 *   认出幽灵屏（DisplayDeviceInfo 1440×3200）后，把“非 ON”的目标状态直接吞掉。
 *
 * 开关（默认关）：
 *   persist.flipunlock.display.keepon=1        装载 + 日志
 *   persist.flipunlock.display.keepon.block=1  真正拦截
 */
object DisplayKeepOnHook {

    private const val PHANTOM_W = 1440
    private const val PHANTOM_H = 3200
    private const val STATE_ON = 2

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayKeepOn) {
            log("DisplayKeepOnHook: DISABLED by persist.flipunlock.display.keepon")
            return
        }
        val blocking = Config.displayKeepOnBlock
        log("DisplayKeepOnHook: setup block=$blocking phantom=${PHANTOM_W}x$PHANTOM_H")

        safeHook("DisplayKeepOnHook") {
            val cls = param.classLoader.loadClass("com.android.server.display.DisplayPowerController")
            var n = 0
            val methods = cls.declaredMethods
            for (m in methods) {
                if (m.name != "animateScreenStateChange") continue
                m.isAccessible = true
                n++
                hook(m) { chain ->
                    // 每次切换都实时读 prop：KSU 脚本可以做到「先用 block 挡住切换，切换完再放开」
                    val liveBlock = Config.displayKeepOnBlock
                    val target = if (chain.args.isNotEmpty()) chain.args[0] as? Int else null
                    val phantom = isPhantom(chain.thisObject)
                    if (phantom && target != null && target != STATE_ON) {
                        if (liveBlock) {
                            log("DisplayKeepOnHook: BLOCKED target=$target (phantom, keep on)")
                            return@hook null
                        }
                        log("DisplayKeepOnHook: pass target=$target (block=false)")
                    }
                    chain.proceed()
                }
            }
            log("DisplayKeepOnHook: hooked animateScreenStateChange x$n (live block prop)")
        }
    }

    /** DisplayPowerController -> mDisplayDevice 的 DisplayDeviceInfo 尺寸判定。 */
    private fun isPhantom(controller: Any?): Boolean {
        if (controller == null) return false
        val device = findDisplayDevice(controller)
        if (device == null) return false
        val info = invokeLocked(device, "getDisplayDeviceInfoLocked")
        if (info == null) return false
        val w = intField(info, "width")
        val h = intField(info, "height")
        return w == PHANTOM_W && h == PHANTOM_H
    }

    private fun findDisplayDevice(obj: Any): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            val fields: Array<Field> = c.declaredFields
            for (f in fields) {
                if (f.name == "mDisplayDevice") {
                    try {
                        f.isAccessible = true
                        return f.get(obj)
                    } catch (t: Throwable) {
                        return null
                    }
                }
            }
            for (f in fields) {
                if (f.type.simpleName == "DisplayDevice") {
                    try {
                        f.isAccessible = true
                        return f.get(obj)
                    } catch (t: Throwable) {
                        return null
                    }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun invokeLocked(obj: Any, name: String): Any? {
        try {
            val m = obj.javaClass.getDeclaredMethod(name)
            m.isAccessible = true
            return m.invoke(obj)
        } catch (t: Throwable) {
            return null
        }
    }

    private fun intField(obj: Any, name: String): Int {
        try {
            val f = obj.javaClass.getField(name)
            return f.getInt(obj)
        } catch (t: Throwable) {
            return -1
        }
    }
}
