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
 * Fix: Before entering overview state (recents view), clear the cached plan so that
 * getSmartRecentsTaskLoadPlan() creates a fresh plan with the latest task list.
 *
 * Process: com.miui.fliphome
 */
object RecentsCacheFix : BaseHook() {

    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        runCatching {
            val recentsViewClass = param.classLoader.loadClass(
                "com.miui.fliphome.recents.views.RecentsView")
            val setOverviewMethod = recentsViewClass.method(
                "setInOverviewState",
                Boolean::class.javaPrimitiveType!!)
            hook(setOverviewMethod, Hooker { chain ->
                val enteringOverview = chain.args[0] as Boolean
                if (enteringOverview) {
                    runCatching {
                        val recentsModelClass = param.classLoader.loadClass(
                            "com.miui.fliphome.recents.RecentsModel")
                        val getInstanceMethod = recentsModelClass.getDeclaredMethod(
                            "getInstance",
                            android.content.Context::class.java)
                        getInstanceMethod.isAccessible = true
                        val context = (chain.thisObject as android.view.View).context
                        val recentsModel = getInstanceMethod.invoke(null, context)
                        val clearMethod = recentsModelClass.getDeclaredMethod(
                            "clearRecentsTaskLoadPlan")
                        clearMethod.isAccessible = true
                        clearMethod.invoke(recentsModel)
                        log("RecentsCacheFix: cleared RecentsTaskLoadPlan cache before overview")
                    }.onFailure { log("RecentsCacheFix: failed to clear cache", it) }
                }
                chain.proceed()
            })
            log("RecentsCacheFix: setInOverviewState hooked — cache cleared before each recents open")
        }.onFailure { log("RecentsCacheFix: hook failed", it) }
    }
}
