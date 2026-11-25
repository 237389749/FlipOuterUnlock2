package com.example.flipunlock.hook.systemui

import android.hardware.SensorManager
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * AOD 靠近感应（non-UI prox）遮蔽 —— 2026-11-25。
 *
 * ## 现象
 * 单外屏机器上 AOD 只亮一下就灭：`logcat` 里能看到
 * ```
 * MiuiDozeScreenBrightnessController: nonui sensor event:33171027, value:2.0
 * MiuiDozeScreenBrightnessController: nonui Prox NEAR, pausing AOD
 * DozeService: setDozeScreenState: 4 → 1          ← 直接熄屏
 * ```
 * 也就是 MIUI 的 AOD 亮度控制器把"非 UI 接近传感器"（type 33171027）的 NEAR 当成
 * "手机在口袋/被遮挡"，于是 `requestState(DOZE_AOD_PAUSING)` → 5s 后 `DOZE_AOD_PAUSED` → 熄屏。
 *
 * ## 为什么单外屏必踩
 * 这颗 prox 在**内屏那一侧**；合盖后内屏贴在一起，它恒报 NEAR。内屏还在时 MIUI 有别的分支
 * 兜（背面光感/折叠态），内屏拆掉 + 属性 1 之后就没有了。
 *
 * ## 做法
 * 在 SystemUI 进程里 hook `SensorManager.getDefaultSensor(int type, boolean triggerMode)`，
 * 对 type == 33171027 返回 **null**。插件 `MiuiDozeScreenBrightnessController` 的构造器会
 * `mNonUiSensor = sensorManager.getDefaultSensor(33171027, true)`，注册任务里有
 * `if (mNonUiSensor == null || registered) return;`（`MiuiDozeScreenBrightnessController.java:185`），
 * 所以返回 null = 这颗传感器不注册 = 不会再有 "Prox NEAR, pausing AOD"。
 *
 * 只遮蔽这一颗；光线传感器（33171029，AOD 亮度档位）保持原样。
 * 开关：`persist.flipunlock.aod.prox_fix`（默认 true），机型 gate：single_outer。
 */
object AodProxFixHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui")

    private const val TAG = "AodProxFix"

    /** Xiaomi 非 UI 接近传感器（AOD/pocket 用）。 */
    private const val SENSOR_NONUI_PROX = 33171027

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.displayAod) {
            log("$TAG: skip, persist.flipunlock.display.aod=0")
            return
        }
        if (!Config.aodProxFix) {
            log("$TAG: skip, persist.flipunlock.aod.prox_fix=0")
            return
        }
        if (!Config.singleOuter) {
            log("$TAG: skip (非单外屏：内屏在时 prox 语义正常)")
            return
        }
        if (currentProcessName() != "com.android.systemui") {
            log("$TAG: skip, process=${currentProcessName()}")
            return
        }
        val method = runCatching {
            SensorManager::class.java
                .getDeclaredMethod(
                    "getDefaultSensor",
                    Int::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!
                )
                .also { it.isAccessible = true }
        }.getOrNull()
        if (method == null) {
            log("$TAG: SensorManager.getDefaultSensor(int,boolean) 不存在, skip")
            return
        }
        var masked = 0
        hook(method) { chain ->
            val type = chain.args.getOrNull(0) as? Int
            if (type == SENSOR_NONUI_PROX) {
                if (masked < 3) {
                    masked++
                    log("$TAG: mask getDefaultSensor($type) → null（避免 Prox NEAR 把 AOD 暂停）")
                }
                null
            } else {
                chain.proceed()
            }
        }
        log("$TAG: ✓ SensorManager.getDefaultSensor hooked (mask type=$SENSOR_NONUI_PROX)")
    }
}
