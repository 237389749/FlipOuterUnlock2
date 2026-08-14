package com.example.flipunlock.hook.system_server

import android.content.ComponentName
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Remove outer screen app launch restrictions. (2026-08-15 v2 强化: 三层 hook)
 *
 * Logic chain (flip2 设备反编译, refMD §43.x):
 *
 *   外屏启动 app / 前台切换
 *     → InterceptActivityController.onForegroundActivityChangedLocked
 *       → handleActivityInterceptionLogic
 *         → 条件4: isInterceptListUnCheckFold(ComponentName) == true  ← 主拦截闸门
 *         → moveToBack + mStartActivityTipView.show()
 *           → StartActivityTipView.show()
 *             → FlipTipView.show()  ← "展开到内屏继续操作"提示(android.appcompat, miui-framework.jar)
 *
 *   isInterceptListUnCheckFold(flip2): 默认 isDialogContinuityEnabled() 时 return true(默认拦截),
 *     各种 LOCAL_POLICY/INTERCEPT_LIST/ALLOW_LIST 列表决定个别 app 例外。
 *
 * 2026-08-15 flip2 实测"展开到内屏继续操作"仍出现 —— 上一版(单 hook isInterceptListUnCheckFold)
 * 静默失效根因: 目标类在 /system_ext/framework/miui-appcompat.appcontinuity.jar,
 *   该 jar 不在 BOOTCLASSPATH(实测 echo $BOOTCLASSPATH 无此条目), param.classLoader
 *   (PathClassLoader) 可能加载不到 → loadClass 抛 ClassNotFoundException 被 safeHook 吞 → 静默失败。
 *   对照: RotationFixHook(services.jar, 主 classpath)在 flip2 生效, 印证 classloader 差异。
 *
 * 修复(v2 三层, 最下游必达):
 *   层1(主): isInterceptListUnCheckFold → false —— 拦截解除(app 外屏直接用)
 *             类查找: param.classLoader + Thread contextClassLoader + 系统 classloader 多路尝试
 *   层2: StartActivityTipView.show() → no-op —— appcontinuity.jar 提示视图(类可加载时)
 *   层3: FlipTipView.show() → no-op —— android.appcompat(android.miui R), miui-framework.jar
 *        在 BOOTCLASSPATH, 一定可加载; 最下游兜底, 提示绝不显示
 *
 * Process: system_server
 * Source: miui-appcompat.appcontinuity.jar(InterceptActivityController/StartActivityTipView)
 *         + miui-framework.jar(FlipTipView)
 */
object AppRestriction {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.enabled) return
        log("AppRestriction: setting up")
        safeHook("AppRestriction") {
            // ── 层1(主): isInterceptListUnCheckFold → false ──
            val iac = findClass("com.android.server.wm.InterceptActivityController", param)
            if (iac != null) {
                runCatching {
                    val method = iac.method("isInterceptListUnCheckFold", ComponentName::class.java)
                    hook(method, replaceResult(false))
                    log("AppRestriction: ✓ 层1 isInterceptListUnCheckFold → false (${iac.name})")
                }.onFailure { log("AppRestriction: 层1 hook failed: ${it.message}") }
            } else {
                log("AppRestriction: 层1 InterceptActivityController 不可加载(非主 classpath), 走层2/3 兜底")
            }

            // ── 层2: StartActivityTipView.show() → no-op(提示视图) ──
            val tip = findClass("com.android.server.wm.StartActivityTipView", param)
            if (tip != null) {
                runCatching {
                    val show = tip.method("show")
                    hook(show) { chain -> null }
                    log("AppRestriction: ✓ 层2 StartActivityTipView.show → no-op (${tip.name})")
                }.onFailure { log("AppRestriction: 层2 hook failed: ${it.message}") }
            }

            // ── 层3: FlipTipView.show() → no-op(android.appcompat, miui-framework.jar 在 BOOTCLASSPATH 必达) ──
            runCatching {
                val flip = Class.forName("android.appcompat.FlipTipView")
                val show = flip.method("show")
                hook(show) { chain -> null }
                log("AppRestriction: ✓ 层3 FlipTipView.show → no-op (${flip.name})")
            }.onFailure { log("AppRestriction: 层3 FlipTipView 不可加载: ${it.message}") }
        }
    }

    /** 多路 classloader 尝试加载(覆盖 appcontinuity.jar 非主 classpath 的情况)。 */
    private fun findClass(name: String, param: SystemServerStartingParam): Class<*>? {
        val loaders = listOfNotNull(
            param.classLoader,
            runCatching { Thread.currentThread().contextClassLoader }.getOrNull(),
            runCatching { ClassLoader.getSystemClassLoader() }.getOrNull(),
        )
        for (l in loaders) {
            try {
                return l.loadClass(name)
            } catch (_: ClassNotFoundException) {
            } catch (_: Throwable) {
            }
        }
        return null
    }
}
