package com.example.flipunlock.hook.camera

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 相机进程内恢复 flip 身份（2026-08-15, 修 flip2 外屏相机倒置 + 3:4 黑边）。
 *
 * 根因（flip2-camera 反编译实锤, 与 §43.7 flip1 旋转锁定同类）:
 *   flip2 属性 1(伪装手机) → 相机 C13576c 静态块 f42310o =
 *     SystemProperties.getInt("persist.sys.multi_display_type") & 255 = 1
 *   → m21996c()(flip 判定)= false → m21418t()(isFlipPhone)= false
 *   → 预览方向补偿 m21404f()(DisplayHelper) 不走 flip 的 180° 逻辑
 *     (flip 手机 rotation!=0 → 强制 180°), 只按普通手机处理
 *   → 外屏预览方向差 180°(横竖都倒)
 *   → 相机按普通手机布局 → 3:4 内屏式界面(左右黑边)
 *
 * 修复: 相机进程内 hook SystemProperties.getInt/get, 对
 *   "persist.sys.multi_display_type" 返回 4(flip) —— 类加载时静态块读到 4 →
 *   m21996c()=true → isFlipPhone → 180° 补偿(解倒置) + flip 布局(解黑边)。
 *   进程内虚拟改属性, 不影响其他进程/属性层真实值(§34.6 HideOuterHook 模式)。
 *
 * 进程: com.android.camera
 */
object CameraFixHook : BaseHook() {

    override val targetPackages = listOf("com.android.camera")

    override fun setupHooks(param: PackageReadyParam) {
        val process = currentProcessName()
        if (process != "com.android.camera") {
            log("CameraFix: skip, process=$process")
            return
        }
        log("CameraFix: loading for ${param.packageName} (process=$process)")
        val cl = processClassLoader(param.classLoader)
        safeHook("CameraFix") {
            val spClass = cl.loadClass("android.os.SystemProperties")
            // getInt(String, int) — 主要路径(C13576c 静态块走这个)
            runCatching {
                val m = spClass.method("getInt", String::class.java, Int::class.javaPrimitiveType!!)
                hook(m) { chain ->
                    val key = chain.args[0] as? String
                    if (key == "persist.sys.multi_display_type") {
                        log("CameraFix: multi_display_type → 4 (flip)")
                        return@hook 4
                    }
                    chain.proceed()
                }
                log("CameraFix: ✓ SystemProperties.getInt(String,int) hooked")
            }.onFailure { log("CameraFix: getInt(String,int) failed: ${it.message}") }
            // getInt(String) — 单参路径兜底
            runCatching {
                val m = spClass.method("getInt", String::class.java)
                hook(m) { chain ->
                    val key = chain.args[0] as? String
                    if (key == "persist.sys.multi_display_type") {
                        return@hook 4
                    }
                    chain.proceed()
                }
                log("CameraFix: ✓ SystemProperties.getInt(String) hooked")
            }.onFailure { log("CameraFix: getInt(String) failed: ${it.message}") }
        }
    }
}
