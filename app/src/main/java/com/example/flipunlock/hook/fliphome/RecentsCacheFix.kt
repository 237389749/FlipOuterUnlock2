package com.example.flipunlock.hook.fliphome

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix: FlipHome recents view sometimes doesn't show all recent tasks.
 *
 * Root cause: RecentsModel caches the RecentsTaskLoadPlan (mRecentsTaskLoadPlan).
 * getSmartRecentsTaskLoadPlan() reuses the cached plan and only calls updateTasks()
 * (blur/lock state refresh) — it does NOT reload the task list from the system.
 * If new tasks were created after the background preload, they won't appear.
 *
 * Previous approach (failed): clear cache in OverviewState.onStateEnabled() —
 *   only covered the OverviewState entry path, missed gesture-based entry and
 *   background preload could re-populate the cache before loadTaskStack().
 *
 * New approach: hook getTaskLoadPlan() → always return null.
 *   This forces getSmartRecentsTaskLoadPlan() to always take the null branch:
 *     if (taskLoadPlan == null) {
 *         taskLoadPlan = taskLoader.createLoadPlan(context);  // fresh data
 *     }
 *   Works regardless of entry path (OverviewState, gesture, preload).
 *
 * Process: com.miui.fliphome
 */
object RecentsCacheFix : BaseHook() {

    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        safeHook("RecentsCacheFix") {
            val recentsModelClass = param.classLoader.loadClass(
                "com.miui.fliphome.recents.RecentsModel")
            val getTaskLoadPlan = recentsModelClass.method("getTaskLoadPlan")
            hook(getTaskLoadPlan, replaceResult(null))
            log("RecentsCacheFix: getTaskLoadPlan() → null (force fresh load every time)")
        }
    }
}
