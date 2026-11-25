package com.example.flipunlock.hook.systemui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * AOD 亮度跟随环境光 —— 把「明亮档」修回高亮（2026-11-25）。
 *
 * ## 现象（refMD `AOD_Full_Chain.md` §11，实机四个熄屏循环）
 * 单外屏下 AOD 能亮，但「开灯/白天看不见，偶尔又很亮」：
 * ```
 * MiuiDozeScreenBrightnessController: sensor event:33171029, value:5.0    ← 明亮室内
 * AodBrightness: doze brightness=8.54597E-4 (0%) floor=10%                ← 结果是最低档
 * ```
 * 同一 boot 的 23:24 那轮传感器报 `4.0` → `AodBrightness ... 1.0 (100%)`。
 *
 * ## 根因
 * `com.miui.aod.doze.MiuiDozeScreenBrightnessController.sensorReactChange(int)`（`:205`）：
 * ```
 * i == 3 → getDefaultLowBrightness()(=1 → 8.5e-4) + scheduleScreenOff(300s)      // 暗
 * i == 5 → getDefaultLowBrightness()(=1)                                        // ★ 明亮反被判最低
 * i == 4 → getDefaultHighBrightness()(=16383 → 1.0)
 * i >= 4 且 Utils.sLightValues != null && len >= 6 → sLightValues[i-4]           // 查表
 * ```
 * bixi 的 device_features 没有 `aod_brightness_level`（/odm、/product、/system 全 grep 无）
 * → `Utils.sLightValues == null` → 查表分支永不执行 → 只剩「4 高 / 5 低」兜底，
 * 而本机明亮室内恰好报 **5**。
 *
 * ## 做法（为什么改事件，而不是改插件方法）
 * 在 SystemUI 进程 hook `SensorManager.registerListener(SensorEventListener, Sensor,
 * int, int, Handler)`（插件注册光线监听用的就是这个 5 参重载，
 * `MiuiDozeScreenBrightnessController.java:183`）：当 `sensor.type == 33171029` 时，
 * 把回调包一层 —— 传感器事件里 `values[0] == 5` 改写为 `4`，再交给插件。
 *
 * 试过但不可行：在 `MiuiDozeService.onCreate` 后从对象图 BFS 找 MIUIAod 的 classloader，
 * 再 hook `sensorReactChange` —— flip2 上 onCreate 时刻 `com.miui.aod.*` 还不在实例图里
 * （实测连续两次 `loader 还没出现`），而 doze 期间又不会再调 onCreate。
 * 钩注册点还有一个好处：**进程启动即装好**，不会错过熄屏后的第一个传感器事件。
 *
 * 只改写这一颗传感器的值；`AodBrightnessHook` 的 10% 下限继续兜底（暗环境）。
 * 开关：`persist.flipunlock.aod.light_map`（默认 true）；机型 gate：single_outer。
 */
object AodLightMapHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui")

    private const val TAG = "AodLightMap"

    /** `xiaomi.sensor.aod`：AOD 环境光档位（1/2=遮挡、3=暗、4=高亮、5=兜底成最低）。 */
    private const val SENSOR_AOD_LIGHT = 33171029

    private const val LEVEL_MAP_FROM = 5f
    private const val LEVEL_MAP_TO = 4f

    /** 包装/改写日志各限几条，避免每次注册都刷。 */
    private var wrappedCount = 0
    private var mappedCount = 0

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.displayAod) {
            log("$TAG: skip, persist.flipunlock.display.aod=0")
            return
        }
        if (!Config.aodLightMap) {
            log("$TAG: skip, persist.flipunlock.aod.light_map=0")
            return
        }
        if (!Config.singleOuter) {
            log("$TAG: skip（非单外屏：内屏在时光感语义正常，不动）")
            return
        }
        if (currentProcessName() != "com.android.systemui") {
            log("$TAG: skip, process=${currentProcessName()}")
            return
        }
        val method = runCatching {
            SensorManager::class.java.getDeclaredMethod(
                "registerListener",
                SensorEventListener::class.java,
                Sensor::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Handler::class.java
            ).also { it.isAccessible = true }
        }.getOrNull()
        if (method == null) {
            log("$TAG: SensorManager.registerListener(5 参) 不存在, skip")
            return
        }
        hook(method) { chain ->
            val listener = chain.args.getOrNull(0) as? SensorEventListener
            val sensor = chain.args.getOrNull(1) as? Sensor
            if (listener != null && sensor?.type == SENSOR_AOD_LIGHT) {
                if (wrappedCount < 3) {
                    wrappedCount++
                    log("$TAG: wrap listener for type=$SENSOR_AOD_LIGHT（$LEVEL_MAP_FROM→$LEVEL_MAP_TO）")
                }
                val wrapped = LevelMapListener(listener) {
                    if (mappedCount < 5) {
                        mappedCount++
                        log("$TAG: light $LEVEL_MAP_FROM → $LEVEL_MAP_TO（改走高亮）")
                    }
                }
                chain.proceed(
                    arrayOf<Any?>(
                        wrapped,
                        sensor,
                        chain.args.getOrNull(2),
                        chain.args.getOrNull(3),
                        chain.args.getOrNull(4)
                    )
                )
            } else {
                chain.proceed()
            }
        }
        log("$TAG: ✓ SensorManager.registerListener hooked (type=$SENSOR_AOD_LIGHT 的 $LEVEL_MAP_FROM 视为 $LEVEL_MAP_TO)")
    }

    /** 光线事件转发：把「明亮档 5」改成「高亮档 4」，其余原样。 */
    private class LevelMapListener(
        private val delegate: SensorEventListener,
        private val onMapped: () -> Unit
    ) : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent) {
            val values = event.values
            if (values.isNotEmpty() && values[0] == LEVEL_MAP_FROM) {
                values[0] = LEVEL_MAP_TO
                onMapped()
            }
            delegate.onSensorChanged(event)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            delegate.onAccuracyChanged(sensor, accuracy)
        }
    }
}
