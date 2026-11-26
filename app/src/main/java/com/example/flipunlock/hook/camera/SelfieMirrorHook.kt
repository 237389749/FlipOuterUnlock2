package com.example.flipunlock.hook.camera

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.processClassLoader
import com.example.flipunlock.hook.util.replaceResult
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 外屏相机「自拍镜像」强制关闭（2026-11-26）。
 *
 * ## 背景（refMD `Camera.md §13`）
 * 折叠/单外屏时相机用**后摄当自拍**，预览与成片默认左右镜像。相机设置里确实有
 * 「自拍镜像」这一项，对应 pref `pref_front_mirror_boolean_key`（**默认 true**），
 * 但入口在 `CameraCommonPreferenceFragment:348` 被 `b.b.a.e.M3()` 挡住，而
 * `M3()` 的语义是 **`isSupportVideoFrontMirror`**（`data/data/i.java:199` 日志实锤）
 * → 折叠/外屏这一档整项不添加，所以设置里**找不到**。
 *
 * 实机验证：把该 pref 置 `false` 后，**预览与成片都不再镜像**（用户确认）。
 *
 * ## 做法
 * 运行时 hook `com.android.camera.data.data.v.U()` → `false`
 * （`U()` = `C1257a.g().h("pref_front_mirror_boolean_key", true)`，是上面 pref 的读点）。
 * **纯运行时**：不写磁盘、不改 app 数据，卸载模块即恢复原生
 * （红线：hook 禁止持久化副作用）。
 *
 * ## 开关
 * `persist.flipunlock.camera.no_mirror`（默认 **true**）。改完需重启相机进程
 * （`am force-stop com.android.camera`）才生效 —— 开关在 `setupHooks` 里读。
 */
object SelfieMirrorHook : BaseHook() {

    override val targetPackages = listOf("com.android.camera")

    private const val TAG = "SelfieMirror"

    override fun setupHooks(param: PackageReadyParam) {
        val process = currentProcessName()
        if (process != "com.android.camera") {
            log("$TAG: skip, process=$process")
            return
        }
        if (!Config.cameraNoMirror) {
            log("$TAG: skip, persist.flipunlock.camera.no_mirror=0")
            return
        }
        val cl = runCatching { processClassLoader(param.classLoader) }.getOrDefault(param.classLoader)
        runCatching {
            val cls = cl.loadClass("com.android.camera.data.data.v")
            val method = cls.getDeclaredMethod("U").apply { isAccessible = true }
            hook(method, replaceResult(false))
            log("$TAG: ✓ data.data.v.U() → false（外屏自拍镜像关闭）")
        }.onFailure { log("$TAG: hook failed: ${it.message}") }
    }
}
