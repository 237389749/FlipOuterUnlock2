package com.example.flipunlock.hook.systemui

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix SystemUI tiny screen behavior on the outer screen.
 *
 * On flip devices, MiuiConfigs.isTinyScreen() returns true on the outer screen,
 * causing: notification icon clipping, modal long-press menu replacing normal
 * menu, carrier text hidden, control center layout changes, etc.
 *
 * Fix:
 *   Hook #1: MiuiConfigs.isTinyScreen(Context) → false
 *            ROOT hook — covers isFlipTinyScreen + isTinyScreenLandscape too.
 *   Hook #2: setMaxIconsAmount(int) → force Integer.MAX_VALUE (defense)
 *   Hook #3: calculateIconXTranslations() after → mMaxIcons defense (defense)
 *
 * Process: systemui
 */
object StatusBarHook : BaseHook() {
    override val targetPackages = listOf("android", "com.android.systemui")

    override fun setupHooks(param: PackageReadyParam) {
        // LSPosed v2.0.1: onPackageReady only fires for "android" in SystemUI process
        if (param.packageName == "android") {
            val proc = currentProcessName()
            if (proc != "com.android.systemui") {
                log("StatusBarHook: skip, process=$proc")
                return
            }
            log("StatusBarHook: pkg=android but process=$proc — installing hooks")
        } else {
            log("StatusBarHook: setupHooks pkg=${param.packageName}")
        }

        safeHook("StatusBarHook") {
            hookIsTinyScreen(param.classLoader)
            hookSetMaxIcons(param.classLoader)
            hookCalculateIcons(param.classLoader)
        }
    }

    private fun currentProcessName(): String? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()

    // ── #1 MiuiConfigs.isTinyScreen(Context) → false (ROOT) ──
    private fun hookIsTinyScreen(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("com.miui.utils.configs.MiuiConfigs")
            val method = cls.method("isTinyScreen", android.content.Context::class.java)
            hook(method, replaceResult(false))
            log("StatusBarHook: MiuiConfigs.isTinyScreen → false")
        }.onFailure { log("StatusBarHook: isTinyScreen hook failed", it) }
    }

    // ── #2 setMaxIconsAmount(int) → force MAX_VALUE (defense) ──
    private fun hookSetMaxIcons(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer")
            val method = cls.method("setMaxIconsAmount", Int::class.javaPrimitiveType!!)
            hook(method) { chain ->
                chain.proceed(arrayOf(Integer.MAX_VALUE))
            }
            log("StatusBarHook: setMaxIconsAmount → MAX_VALUE")
        }.onFailure { log("StatusBarHook: setMaxIconsAmount hook failed", it) }
    }

    // ── #3 calculateIconXTranslations() after → ensure mMaxIcons = MAX_VALUE (defense) ──
    private fun hookCalculateIcons(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer")
            val method = cls.method("calculateIconXTranslations")
            hook(method, after { chain, result ->
                chain.thisObject?.setField("mMaxIcons", Integer.MAX_VALUE)
                result
            })
            log("StatusBarHook: calculateIconXTranslations → mMaxIcons defense")
        }.onFailure { log("StatusBarHook: calculateIconXTranslations hook failed", it) }
    }

}
