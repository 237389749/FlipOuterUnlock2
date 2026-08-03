package com.example.flipunlock.hook.fliphome

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix: FlipHome recents view sometimes doesn't show all recent tasks.
 *
 * Root cause: RecentsModel caches the RecentsTaskLoadPlan (mRecentsTaskLoadPlan).
 * When the user opens recents, getSmartRecentsTaskLoadPlan() reuses the cached plan
 * and only calls updateTasks() (blur/lock state refresh) — it does NOT reload the
 * task list from the system. If new tasks were created after the background preload,
 * they won't appear.
 *
 * Entry point (refMD FlipRes): OverviewState.onStateEnabled() → RecentsView.reloadStackView()
 *   → loadTaskStack() → getSmartRecentsTaskLoadPlan()
 *   If mRecentsTaskLoadPlan != null → only updateTasks() (stale data).
 *   If mRecentsTaskLoadPlan == null → createLoadPlan() + preloadTasks() (fresh data).
 *
 * Fix: In OverviewState.onStateEnabled(), clear the cached plan BEFORE reloadStackView()
 *   so that getSmartRecentsTaskLoadPlan() creates a fresh plan with the latest task list.
 *
 * Process: com.miui.fliphome
 */
object RecentsCacheFix : BaseHook() {

    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        runCatching {
            val overviewStateClass = param.classLoader.loadClass(
                "com.miui.fliphome.recents.OverviewState")
            val onStateEnabledMethod = overviewStateClass.method(
                "onStateEnabled",
                param.classLoader.loadClass("com.miui.fliphome.FlipLauncher"))
            hook(onStateEnabledMethod, Hooker { chain ->
                runCatching {
                    val recentsModelClass = param.classLoader.loadClass(
                        "com.miui.fliphome.recents.RecentsModel")
                    val getInstanceMethod = recentsModelClass.getDeclaredMethod(
                        "getInstance",
                        android.content.Context::class.java)
                    getInstanceMethod.isAccessible = true
                    val context = (chain.args[0] as android.content.Context)
                    val recentsModel = getInstanceMethod.invoke(null, context)
                    val clearMethod = recentsModelClass.getDeclaredMethod(
                        "clearRecentsTaskLoadPlan")
                    clearMethod.isAccessible = true
                    clearMethod.invoke(recentsModel)
                    log("RecentsCacheFix: cleared RecentsTaskLoadPlan cache before overview")
                }.onFailure { log("RecentsCacheFix: failed to clear cache", it) }
                chain.proceed()
            })
            log("RecentsCacheFix: OverviewState.onStateEnabled hooked — cache cleared before each recents open")
        }.onFailure { log("RecentsCacheFix: hook failed", it) }
    }
}
