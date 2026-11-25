package com.example.flipunlock.hook.systemui

import android.service.dreams.DreamService
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * AOD 亮度：全量日志 + 可选**亮度下限**（2026-11-25）。
 *
 * 背景（见 refMD `AOD_Full_Chain.md` §8/§9）：单外屏机器上 AOD **是亮的，但只有 ~5 nits** ——
 *   暗处看得见、白天看不见，投屏/截图却很亮，于是看起来像"没显示"。
 *   链：`xiaomi.sensor.aod`(33171029) → `MiuiDozeScreenBrightnessController.sensorReactChange(i)`：
 *     - i==3 → `getDefaultLowBrightness()`=1（+5 分钟后关屏）
 *     - i==4 → `getDefaultHighBrightness()`=16383
 *     - i==5 → `getDefaultLowBrightness()`=1     ← 本机实测常驻 5，于是恒为最低亮度
 *     - i>=4 且 device_features 有 `aod_brightness_level` 数组 → 用表；**bixi.xml 里没有这个键**，
 *       所以只剩上面的兜底分支。
 *   1/16383 最终经 `DreamServiceUtils.convertToFloatBrightness` 变成 0.00085/1.0，
 *   汇到框架 `DreamService.setDozeScreenBrightness(float)` —— 本文件就钉在这里。
 *
 * 行为：
 *   - `persist.flipunlock.aod.brightness` = 0（默认）→ **只打日志，不改**（纯诊断）；
 *   - = N（1..100）→ AOD 亮度下限 N%，任何低于 N% 的 doze 亮度都被抬到 N%。
 *
 * 为什么钉 float 而不是改插件的 int：插件的 `setDozeScreenBrightness(int)` 会经过
 *   `MiuiDozeBrightnessTimeoutAdapter` 5 s 回放和 `DozeScreenState` 的状态机，点多且会被覆盖；
 *   `DreamService.setDozeScreenBrightness(float)` 是**唯一出口**，改这里不会被绕开。
 *
 * 开关：`persist.flipunlock.aod.brightness`（0=关/只记录）。改完不需要重启 SystemUI（下次 doze 生效）。
 */
object AodBrightnessHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui")

    private const val TAG = "AodBrightness"

    private var lastKey = Int.MIN_VALUE

    override fun setupHooks(param: PackageReadyParam) {
        val floorPercent = Config.aodBrightnessFloor
        val trace = Config.aodTrace
        if (!trace && floorPercent <= 0) {
            log("$TAG: skip (trace=off, floor=0)")
            return
        }
        if (currentProcessName() != "com.android.systemui") {
            log("$TAG: skip, process=${currentProcessName()}")
            return
        }
        val floor = if (floorPercent > 0) (floorPercent / 100f) else 0f
        val method = runCatching {
            DreamService::class.java
                .getDeclaredMethod("setDozeScreenBrightness", Float::class.javaPrimitiveType!!)
                .also { it.isAccessible = true }
        }.getOrNull()
        if (method == null) {
            log("$TAG: DreamService.setDozeScreenBrightness(float) 不存在, skip")
            return
        }
        hook(method) { chain ->
            val v = chain.args.getOrNull(0) as? Float ?: return@hook chain.proceed()
            val key = (v * 10000).toInt()
            if (key != lastKey) {
                lastKey = key
                log("$TAG: doze brightness=$v (${(v * 100).toInt()}%)" + if (floor > 0) " floor=$floorPercent%" else "")
            }
            if (floor > 0f && v < floor) {
                chain.proceed(arrayOf<Any?>(floor))
            } else {
                chain.proceed()
            }
        }
        log(
            "$TAG: ✓ DreamService.setDozeScreenBrightness hooked " +
                "(trace=$trace, floor=${if (floor > 0) "${floorPercent}%" else "off"})"
        )
    }
}
