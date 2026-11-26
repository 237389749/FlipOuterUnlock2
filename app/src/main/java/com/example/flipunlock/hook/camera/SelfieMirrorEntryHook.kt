package com.example.flipunlock.hook.camera

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.processClassLoader
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

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
 * ## 做法
 * 只在"设置页正在构建"的那段时间里让 `M3()` 返回 true：
 * - hook `CameraCommonPreferenceFragment.addCommonPreferences1()`：进入时置标志，退出时清掉；
 * - hook 各 `C019x` 配置类的 `M3()`（无参）：标志为真 → 返回 true，否则原样 `proceed()`。
 *
 * 这样其它调用点（`VideoModule` 的视频前摄镜像、`CameraSettingsSearchProvider`、
 * `ProModule` 等）仍然看到真实值，不会顺手打开别的功能。
 *
 * 开关：`persist.flipunlock.camera.mirror_entry`（默认 **true**）。改后需重启相机进程。
 * 入口放出来后，"开/关镜像"仍然由相机自己的 pref `pref_front_mirror_boolean_key` 决定。
 */
object SelfieMirrorEntryHook : BaseHook() {

    override val targetPackages = listOf("com.android.camera")

    private const val TAG = "MirrorEntry"

    /** 设置页构建期间为 true；`M3()` 见 true 即放行该项。 */
    @Volatile
    private var buildingCommonPrefs = false

    /** 已观察到 M3() 被放行的次数（只用于日志限流）。 */
    private var granted = 0

    /** 本 build 里所有 `boolean M3()` 的实现类（反编译 classes3.dex 枚举；换包名会失败，只打日志）。 */
    private val M3_CLASSES = listOf(
        "defpackage.C0193", "defpackage.C0194", "defpackage.C0195",
        "defpackage.C0198", "defpackage.C0199", "defpackage.C0200",
        "defpackage.C0201", "defpackage.C0202", "defpackage.C0204",
        "defpackage.C0205", "defpackage.C0206", "defpackage.C0210",
    )

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

        // ② 各配置类的 M3()（= isSupportVideoFrontMirror）
        var hooked = 0
        for (name in M3_CLASSES) {
            runCatching {
                val cls = cl.loadClass(name)
                val m = cls.getDeclaredMethod("M3").apply { isAccessible = true }
                hook(m) { chain ->
                    if (buildingCommonPrefs) {
                        if (granted < 3) {
                            granted++
                            log("$TAG: M3() → true（设置页构建设置项）")
                        }
                        true
                    } else {
                        chain.proceed()
                    }
                }
                hooked++
            }.onFailure { log("$TAG: $name.M3 hook failed: ${it.message}") }
        }
        log("$TAG: ✓ M3 hooked $hooked/${M3_CLASSES.size} 个实现类")
    }
}
