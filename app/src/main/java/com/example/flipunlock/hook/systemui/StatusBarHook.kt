package com.example.flipunlock.hook.systemui

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Expand status bar notification icon limit on the outer screen.
 *
 * On flip devices, SystemUI reduces NotificationIconContainer.mMaxIcons on
 * tiny screen, clipping notification icons to fewer than normal.
 *
 * Fix:
 *   Hook #1: setMaxIconsAmount(int) → force Integer.MAX_VALUE
 *            Prevents any code path from reducing the icon limit.
 *   Hook #2: calculateIconXTranslations() after-hook → defense-in-depth,
 *            ensures mMaxIcons is MAX_VALUE before layout calculation.
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
            hookSetMaxIcons(param.classLoader)
            hookCalculateIcons(param.classLoader)
        }
    }

    private fun currentProcessName(): String? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()

    // ── #1 setMaxIconsAmount(int) → force MAX_VALUE ──
    private fun hookSetMaxIcons(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer")
            val method = cls.method("setMaxIconsAmount", Int::class.javaPrimitiveType!!)
            hook(method) { chain ->
                chain.proceed(Integer.MAX_VALUE)
            }
            log("StatusBarHook: setMaxIconsAmount → MAX_VALUE")
        }.onFailure { log("StatusBarHook: setMaxIconsAmount hook failed", it) }
    }

    // ── #2 calculateIconXTranslations() after → ensure mMaxIcons = MAX_VALUE ──
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
