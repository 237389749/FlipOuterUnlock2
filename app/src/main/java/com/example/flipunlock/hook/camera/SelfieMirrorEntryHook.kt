package com.example.flipunlock.hook.camera

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.processClassLoader
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field

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
 * hook `addCommonPreferences1()` 的 after：如果设置页里没有这一项，就用 Fragment **自己的**
 * `addCheckBoxPreference(PreferenceGroup, key, default, titleRes, summaryRes)` 补加，
 * 再按当前 pref 值（`data.data.v.U()`）设置勾选状态。
 *
 * 这样：不动 `M3()`（视频前摄镜像/搜索索引等调用点不受影响），也不写任何持久化；
 * 用户勾/取消时走 Fragment 原生 `onPreferenceChange → updateSharePreference`，
 * 写的就是相机自己的 `pref_front_mirror_boolean_key`。
 *
 * 开关：`persist.flipunlock.camera.mirror_entry`（默认 **true**）；改后需重启相机进程。
 */
object SelfieMirrorEntryHook : BaseHook() {

    override val targetPackages = listOf("com.android.camera")

    private const val TAG = "MirrorEntry"

    /** 相机进程的 classloader（readMirrorPref 用）。 */
    private var appCl: ClassLoader? = null

    private const val KEY = "pref_front_mirror_boolean_key"
    private const val TITLE_RES = 2132021375   // pref_front_mirror_title
    private const val SUMMARY_RES = 2132021368 // pref_front_mirror_desc

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
        appCl = cl
        runCatching {
            val frag = cl.loadClass("com.android.camera.fragment.settings.CameraCommonPreferenceFragment")
            val m = frag.getDeclaredMethod("addCommonPreferences1").apply { isAccessible = true }
            hook(m, com.example.flipunlock.hook.util.after { chain, result ->
                runCatching { injectIfMissing(chain.thisObject) }
                    .onFailure { log("$TAG: inject failed: ${it.message}") }
                result
            })
            log("$TAG: ✓ addCommonPreferences1 hooked（缺项时补加「自拍镜像」）")
        }.onFailure { log("$TAG: hook failed: ${it.message}") }
    }

    /** 设置页里没有该项就补加，并把勾选状态同步成当前 pref。 */
    private fun injectIfMissing(fragment: Any?) {
        if (fragment == null) return
        val screen = findFieldValue(fragment, "mPreferenceGroup") ?: run {
            log("$TAG: mPreferenceGroup 取不到")
            return
        }
        if (findPreferenceKey(screen, KEY) != null) {
            log("$TAG: 已存在「$KEY」，跳过")
            return
        }
        val add = fragment.javaClass.methods.firstOrNull { it.name == "addCheckBoxPreference" && it.parameterCount == 5 }
            ?: run { log("$TAG: addCheckBoxPreference(5) 没找到"); return }
        val created = add.invoke(fragment, screen, KEY, true, TITLE_RES, SUMMARY_RES)
        val current = readMirrorPref()
        runCatching {
            created?.javaClass?.methods?.firstOrNull { it.name == "setChecked" }?.invoke(created, current)
        }
        log("$TAG: ✓ 已补加「自拍镜像」到设置页（当前值=$current）")
    }

    /** `data.data.v.U()` = 相机 pref `pref_front_mirror_boolean_key`（默认 true）。 */
    private fun readMirrorPref(): Boolean = runCatching {
        val cl = appCl ?: error("appCl null")
        val cls = cl.loadClass("com.android.camera.data.data.v")
        cls.getDeclaredMethod("U").apply { isAccessible = true }.invoke(null) as Boolean
    }.getOrDefault(true)

    /** PreferenceScreen/Group 内部列表（字段名 `f7751g0`）里按 key（`f7740m`）找。 */
    private fun findPreferenceKey(group: Any, key: String): Any? {
        val list = findFieldValue(group, "f7751g0") as? List<*> ?: return null
        for (item in list) {
            if (item == null) continue
            val k = findFieldValue(item, "f7740m") as? String
            if (k == key) return item
        }
        return null
    }

    /** 沿父类链找字段（混淆名在父类里）。 */
    private fun findFieldValue(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            val f: Field? = runCatching { c!!.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                return runCatching {
                    f.isAccessible = true
                    f.get(obj)
                }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }
}
