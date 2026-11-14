package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Field

/**
 * 单外屏（拔掉内屏）保命 hook：**阻止框架把 display 0（幽灵内屏）掉电/disable**。
 *
 * ── 背景（refMD `Inner_Screen_Bootloop_TEE_Watchdog.md` §8–§11）──
 * 拔掉内屏后，bootloader 仍按 `msm_drm.dsi_display0=qcom,mdss_dsi_nt37801_wqhd_plus_vid`
 * 给内核一个“默认面板”，于是 display 0 是一个 1440×3200 的**幽灵内屏**。
 * 只要框架对它做一次 disable / 熄屏 / 状态切换，TEE 侧 `si_core` 的 ADCI 调用就永久阻塞，
 * 连累 Gunyah 看门狗 → **63~66s PS_HOLD 硬复位**。
 *
 * 实测（2026-11-15）：
 *   - `svc power stayon true` 后系统能完整启动（boot_completed=1）且 200s+ 不复位；
 *   - 但任何 device_state 切换（0 / 6）都会让 phantom 掉电 → 立刻复位；
 *   - 提前到 phantom 上电之前切状态也一样炸 —— 说明“disable display 0”这个动作本身就是雷。
 *
 * ── 做法 ──
 * hook `DisplayPowerController.animateScreenStateChange(int target, boolean)`：
 * 当该 controller 对应的是幽灵屏（DisplayDeviceInfo 1440×3200）且目标状态不是 ON(2) 时，
 * **直接跳过**这次状态切换（不 proceed），从而不让它的电源状态被真正改动。
 *
 * ── 开关（默认关，必须显式打开）──
 *   persist.flipunlock.display.keepon=1        总开关（装载 hook + 日志）
 *   persist.flipunlock.display.keepon.block=1  真正拦截（0 = 只记日志，便于先取证）
 *
 * 进程: system_server。
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
        log("DisplayKeepOnHook: setting up (block=$blocking, phantom=${PHANTOM_W}x$PHANTOM_H)")

        safeHook("DisplayKeepOnHook") {
            val cls = param.classLoader.loadClass("com.android.server.display.DisplayPowerController")
            var count = 0
            for (m in cls.declaredMethods) {
                if (m.name != "animateScreenStateChange") continue
                m.isAccessible = true
                count++
                hook(m) { chain ->
                    val self = chain.thisObject
                    val target = chain.args.getOrNull(0) as? Int
                    val phantom = isPhantom(self)
                    if (phantom) {
                        log("DisplayKeepOnHook: animateScreenStateChange target=$target phantom=true block=$blocking")
                        if (blocking && target != null && target != STATE_ON) {
                            log("DisplayKeepOnHook: ✓ 拦住 phantom 状态切换 → 保持现状")
                            return@hook null
                        }
                    }
                    chain.proceed()
                }
            }
            log("DisplayKeepOnHook: hooked animateScreenStateChange x$count")
        }

        // 兜底：DisplayPowerState.setScreenState 是最后写硬件状态的点；
        // 这里只做日志（block 时不 proceed），同样以 1440×3200 识别幽灵屏。
        safeHook("DisplayKeepOnHook.setScreenState") {
            val cls = param.classLoader.loadClass("com.android.server.display.DisplayPowerState")
            var count = 0
            for (m in cls.declaredMethods) {
                if (m.name != "setScreenState") continue
                m.isAccessible = true
                count++
                hook(m) { chain ->
                    val self = chain.thisObject
                    val state = chain.args.getOrNull(0) as? Int
                    val phantom = isPhantomViaField(self)
                    if (phantom) {
                        log("DisplayKeepOnHook: setScreenState state=$state phantom=true")
                        if (blocking && state != null && state != STATE_ON) {
                            log("DisplayKeepOnHook: ✓ 拦住 setScreenState($state)")
                            return@hook true
                        }
                    }
                    chain.proceed()
                }
            }
            log("DisplayKeepOnHook: hooked setScreenState x$count")
        }
    }

    /** 从 DisplayPowerController 上取 DisplayDevice，再读 DisplayDeviceInfo 的宽高。 */
    private fun isPhantom(controller: Any?): Boolean {
        if (controller == null) return false
        return runCatching {
            val dev = findField(controller, "mDisplayDevice")
                ?: findFieldByTypeName(controller, "DisplayDevice")
                ?: return false
            sizeOf(dev)
        }.getOrDefault(false)
    }

    /** DisplayPowerState 里没有 DisplayDevice 字段时，按 mDisplayId 兜底：display 0 视为幽灵屏候选。 */
    private fun isPhantomViaField(pps: Any?): Boolean {
        if (pps == null) return false
        return runCatching {
            val dev = findFieldByTypeName(pps, "DisplayDevice")
            if (dev != null) return sizeOf(dev)
            val idField = findField(pps, "mDisplayId") ?: return false
            idField.isAccessible = true
            (idField.get(pps) as? Int) == 0
        }.getOrDefault(false)
    }

    private fun sizeOf(displayDevice: Any?): Boolean {
        if (displayDevice == null) return false
        val info = runCatching {
            displayDevice.javaClass.getDeclaredMethod("getDisplayDeviceInfoLocked")
                .apply { isAccessible = true }
                .invoke(displayDevice)
        }.getOrNull() ?: return false
        val w = runCatching { info.javaClass.getField("width").getInt(info) }.getOrDefault(-1)
        val h = runCatching { info.javaClass.getField("height").getInt(info) }.getOrDefault(-1)
        return w == PHANTOM_W && h == PHANTOM_H
    }

    private fun findField(obj: Any, name: String): Any? = runCatching {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            val f: Field? = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return f.get(obj)
            }
            c = c.superclass
        }
        null
    }.getOrNull()

    private fun findFieldByTypeName(obj: Any, typeName: String): Any? = runCatching {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            val f = c.declaredFields.firstOrNull { it.type.simpleName == typeName }
            if (f != null) {
                f.isAccessible = true
                return f.get(obj)
            }
            c = c.superclass
        }
        null
    }.getOrNull()
}
