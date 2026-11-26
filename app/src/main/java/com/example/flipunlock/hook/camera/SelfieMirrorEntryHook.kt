package com.example.flipunlock.hook.camera

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.processClassLoader
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 让「自拍镜像」在折叠（外屏）下也出现在相机设置里（2026-11-26）。
 *
 * ## 为什么会被隐藏（refMD `Camera.md §13`）
 * `CameraCommonPreferenceFragment.addCommonPreferences1():348`：
 * ```java
 * if (bVar.e.M3()) {                       // M3() = isSupportVideoFrontMirror
 *     addCheckBoxPreference(cat, "pref_front_mirror_boolean_key", true, ...);
 * }
 * ```
 * 折叠/外屏这一档 `M3()` 为 false → 整个 checkbox **不添加**（不是禁用）。
 *
 * ## 做法（不依赖混淆类名）
 * 1. hook `CameraCommonPreferenceFragment.addCommonPreferences1()`：进入置标志、退出清除
 *    （这就是"设置页正在构建"的窗口）；
 * 2. 运行期从 `Se.b` 的单例（嵌套类 `a` 静态字段）拿到配置对象 `Se.b.e`，
 *    再对**它自己的 class** 上的 `M3()` 装 hook —— 标志为真时返回 true，其余 `proceed()`。
 *
 * 这样其它调用点（`VideoModule` 的视频前摄镜像、`CameraSettingsSearchProvider`、`ProModule`…）
 * 仍看到真实值，不会顺手打开别的功能。
 *
 * 开关：`persist.flipunlock.camera.mirror_entry`（默认 **true**）；改后需重启相机进程。
 * 入口出现后，开关值仍由相机自己的 pref `pref_front_mirror_boolean_key` 决定。
 */
object SelfieMirrorEntryHook : BaseHook() {

    override val targetPackages = listOf("com.android.camera")

    private const val TAG = "MirrorEntry"

    /** 设置页构建期间为 true。 */
    @Volatile
    private var buildingCommonPrefs = false

    private var granted = 0

    override fun setupHooks(param: PackageReadyParam) {
        if (currentProcessName() != "com.android.camera") {
            log("$TAG: skip, process=${currentProcessName()}")
            return
        }
        if (!Config.cameraMirrorEntry) {
            log("$TAG: skip, persist.flipunlock.camera.mirror_entry=0")
            return
        }
        val cl = runCatching { processClassLoader(param.classLoader) }.getOrDefault(param.classLoader)

        // ① 设置页构建窗口
        runCatching {
            val frag = cl.loadClass("com.android.camera.fragment.settings.CameraCommonPreferenceFragment")
            val m = frag.getDeclaredMethod("addCommonPreferences1").apply { isAccessible = true }
            hook(m) { chain ->
                buildingCommonPrefs = true
                try {
                    chain.proceed()
                } finally {
                    buildingCommonPrefs = false
                }
            }
            log("$TAG: ✓ addCommonPreferences1 hooked（设置页构建窗口）")
        }.onFailure { log("$TAG: addCommonPreferences1 hook failed: ${it.message}") }

        // ② 动态定位配置对象的 M3()
        runCatching {
            val sb = cl.loadClass("Se.b")
            val inst = findConfigInstance(sb)
            if (inst == null) {
                log("$TAG: Se.b 实例没找到，M3 未 hook")
                return@runCatching
            }
            val cfg = sb.getDeclaredField("e").apply { isAccessible = true }.get(inst)
                ?: run { log("$TAG: Se.b.e 为 null"); return@runCatching }
            val m = findMethod(cfg.javaClass, "M3")
            if (m == null) {
                log("$TAG: ${cfg.javaClass.name}.M3 没找到")
                return@runCatching
            }
            hook(m) { chain ->
                if (buildingCommonPrefs) {
                    if (granted < 3) {
                        granted++
                        log("$TAG: M3() → true（设置页构建设置项, cfg=${cfg.javaClass.simpleName}）")
                    }
                    true
                } else {
                    chain.proceed()
                }
            }
            log("$TAG: ✓ ${cfg.javaClass.name}.M3 hooked（仅设置页构建时放行）")
        }.onFailure { log("$TAG: M3 hook failed: ${it.message}") }
    }

    /** 从 `Se.b` 的嵌套类静态实例（通常 `a`）取到单例；找不到就自己 new 一个。 */
    private fun findConfigInstance(sb: Class<*>): Any? {
        for (nested in runCatching { sb.declaredClasses }.getOrDefault(emptyArray())) {
            for (f in runCatching { nested.declaredFields }.getOrDefault(emptyArray())) {
                if (!Modifier.isStatic(f.modifiers)) continue
                val v = runCatching { f.isAccessible = true; f.get(null) }.getOrNull() ?: continue
                if (sb.isInstance(v)) return v
            }
        }
        return runCatching {
            sb.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull()
    }

    /** 沿父类链找 no-arg 的 `M3`（混淆后可能声明在父类）。 */
    private fun findMethod(cls: Class<*>, name: String): Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            runCatching {
                val m = c!!.getDeclaredMethod(name)
                if (m.parameterCount == 0 && m.returnType == java.lang.Boolean.TYPE) {
                    m.isAccessible = true
                    return m
                }
            }
            c = c.superclass
        }
        return null
    }
}
