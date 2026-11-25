package com.example.flipunlock.hook.systemui

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.after
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.processClassLoader
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
 * 同一 boot 里 23:24 那轮传感器报 `4.0` → `AodBrightness ... 1.0 (100%)`。
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
 * ## 做法
 * 在插件自己的 classloader 上 hook `sensorReactChange(int)`，把 **5 改写成 4**（其余原样）。
 * 不碰亮度常量、不碰 doze 状态：暗环境（3）仍走原生低档 + 5 分钟熄屏语义。
 *
 * ## 为什么必须拿插件 classloader
 * `com.miui.aod.*` 由 MIUIAod 插件 APK 单独加载（见 `AodHook` 头注释），
 * `onPackageReady` 的 classLoader 看不到；这里从 `MiuiDozeService` 实例的对象图反查
 * （BFS 同 `AodHook.pluginClassLoaderFrom`）。装不上不置位，下次 dream 再试。
 *
 * 开关：`persist.flipunlock.aod.light_map`（默认 true）；机型 gate：single_outer。
 */
object AodLightMapHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui")

    private const val TAG = "AodLightMap"

    /** `sensorReactChange` 里「明亮」的那两个档：4=高亮、5=兜底成最低。把 5 当 4。 */
    private const val LEVEL_MAP_FROM = 5
    private const val LEVEL_MAP_TO = 4

    /** 已装过 hook 的 classloader（同一个 loader 只装一次；插件重建 loader 会再装）。 */
    private val hookedLoaders = mutableSetOf<ClassLoader>()

    /** 档位日志去重。 */
    private var lastLevel = Int.MIN_VALUE

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
        val cl = runCatching { processClassLoader(param.classLoader) }.getOrDefault(param.classLoader)
        val onCreate = runCatching {
            cl.loadClass("com.android.keyguard.doze.MiuiDozeService")
                .getDeclaredMethod("onCreate")
                .apply { isAccessible = true }
        }.getOrNull()
        if (onCreate == null) {
            log("$TAG: MiuiDozeService.onCreate 不存在, skip")
            return
        }
        // onCreate 时插件实例已挂到 service 上（setDozeRequester 在 onCreate 内），
        // 从实例图能拿到 MIUIAod 的 classloader。
        hook(onCreate, after { chain, result ->
            install(chain.thisObject, cl, param.classLoader)
            result
        })
        log("$TAG: ✓ MiuiDozeService.onCreate hooked（等插件实例后装 sensorReactChange）")
    }

    private fun install(service: Any?, vararg loaders: ClassLoader) {
        val candidates = mutableListOf<ClassLoader>()
        loaders.forEach { candidates.add(it) }
        pluginClassLoaderFrom(service)?.let { candidates.add(it) }

        for (loader in candidates.distinct()) {
            if (!hookedLoaders.add(loader)) continue
            val method = runCatching {
                loader.loadClass("com.miui.aod.doze.MiuiDozeScreenBrightnessController")
                    .getDeclaredMethod("sensorReactChange", Int::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }
            }.getOrNull() ?: continue
            runCatching {
                hook(method) { chain ->
                    val level = chain.args.getOrNull(0) as? Int
                    if (level != null && level != lastLevel) {
                        lastLevel = level
                        log(
                            "$TAG: light level=$level" +
                                if (level == LEVEL_MAP_FROM) " → $LEVEL_MAP_TO（明亮档改走高亮）" else ""
                        )
                    }
                    if (level == LEVEL_MAP_FROM) {
                        chain.proceed(arrayOf<Any?>(LEVEL_MAP_TO))
                    } else {
                        chain.proceed()
                    }
                }
                log("$TAG: ✓ sensorReactChange hooked on cl=${loader.javaClass.simpleName}")
                return
            }.onFailure { log("$TAG: sensorReactChange hook failed: ${it.message}") }
        }
        log("$TAG: sensorReactChange 未装上（loader 还没出现），下次 dream 再试")
    }

    /**
     * 从对象图反查 AOD 插件 classloader（BFS，深度 ≤6，只沿 miui.aod 分支）。
     * 与 `AodHook.pluginClassLoaderFrom` 同策略；那边是 flip1-only，这里 flip2 单外屏用。
     */
    private fun pluginClassLoaderFrom(root: Any?): ClassLoader? {
        if (root == null) return null
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Any>()
        queue.add(root)
        var level = 1
        var depth = 0
        while (queue.isNotEmpty() && depth <= 6) {
            var next = 0
            repeat(level) {
                if (queue.isEmpty()) return@repeat
                val obj = queue.removeFirst()
                if (!visited.add(System.identityHashCode(obj))) return@repeat
                val cls = obj.javaClass
                if (cls.name.startsWith("com.miui.aod")) return cls.classLoader
                for (f in cls.declaredFields) {
                    val v = runCatching {
                        f.isAccessible = true
                        f.get(obj)
                    }.getOrNull() ?: continue
                    val n = v.javaClass.name
                    if (n.startsWith("com.miui.aod") || n.startsWith("com.android.keyguard")) {
                        queue.add(v)
                        next++
                    }
                }
            }
            depth++
            level = next
        }
        return null
    }
}
