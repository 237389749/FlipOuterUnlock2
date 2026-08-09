package com.example.flipunlock.hook.identity

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Camera 进程属性反向覆盖（resetprop 方案的配套）。
 *
 * resetprop persist.sys.multi_display_type=1 全局生效（flip→普通手机），
 * 但 camera 有 flip 专属布局（isFlipDevice→false 导致布局错乱）。
 * 本 hook 在 camera 进程内把 multi_display_type 读取改回 4（flip 身份），
 * 让 camera 走原生 flip 布局。
 *
 * 与 DeviceIdentityHook 冲突说明：DeviceIdentityHook 的 hookSystemProperties
 * （wildcard →1）已被注释（resetprop 替代）；camera 反向覆盖（→4）独立。
 * 若 DeviceIdentityHook 未来恢复，需跳过 camera 进程避免冲突。
 *
 * 进程：com.android.camera
 */
object CameraReverseHook : BaseHook() {
    override val targetPackages = listOf("com.android.camera")

    override fun setupHooks(param: PackageReadyParam) {
        log("CameraReverseHook: loading for ${param.packageName}")
        safeHook("CameraReverseHook") {
            runCatching {
                val sp = param.classLoader.loadClass("android.os.SystemProperties")
                hook(sp.method("getInt", String::class.java, Int::class.java)) { chain ->
                    if (chain.args[0] == "persist.sys.multi_display_type") 4 else chain.proceed()
                }
                log("CameraReverseHook: ✓ multi_display_type→4 in camera")
            }.onFailure { log("CameraReverseHook: android SystemProperties failed: ${it.message}") }
            runCatching {
                val sp = param.classLoader.loadClass("miuix.core.util.SystemProperties")
                hook(sp.method("getInt", String::class.java, Int::class.java)) { chain ->
                    if (chain.args[0] == "persist.sys.multi_display_type") 4 else chain.proceed()
                }
                log("CameraReverseHook: ✓ miuix SystemProperties→4 in camera")
            }.onFailure { log("CameraReverseHook: miuix SystemProperties failed: ${it.message}") }
        }
    }
}
