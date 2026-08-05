package com.example.flipunlock.hook.fliphome

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix: FlipHome recents view doesn't show all recent tasks.
 *
 * Two root causes, both fixed here:
 *
 * 1. Cache staleness: RecentsModel caches the RecentsTaskLoadPlan.
 *    getSmartRecentsTaskLoadPlan() reuses the cached plan and only calls
 *    updateTasks() (blur/lock refresh) — NOT a full task list reload.
 *    Fix: hook getSmartRecentsTaskLoadPlan() → clearRecentsTaskLoadPlan()
 *    before proceeding, forcing a fresh plan with preloadTasks().
 *
 * 2. Task filtering (PRIMARY cause): ActivityManagerWrapper.needRemoveTask()
 *    calls LauncherModel.canShowOutScreenWithComponent() which checks
 *    mLauncherApps.getValidPkgSet(). After a cold boot, the valid package
 *    set is not yet populated → apps like Settings are silently filtered
 *    from recents. Entering the "编辑" interface triggers syncAppSettingData()
 *    which refreshes the set → problem disappears.
 *    Fix: hook needRemoveTask(GroupedRecentTaskInfoCompat) → false.
 *
 * Previous approach (failed): hook getTaskLoadPlan() → return null.
 *   getTaskLoadPlan() is a trivial getter that R8 inlines — hook never fires.
 *
 * Process: com.miui.fliphome
 */
object RecentsCacheFix : BaseHook() {

    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        // Fix #1: cache staleness — force fresh task load every time
        safeHook("RecentsCacheFix-cache") {
            val recentsModelClass = param.classLoader.loadClass(
                "com.miui.fliphome.recents.RecentsModel")
            val getSmartPlan = recentsModelClass.method(
                "getSmartRecentsTaskLoadPlan",
                android.content.Context::class.java,
                Int::class.javaPrimitiveType!!)
            hook(getSmartPlan, Hooker { chain ->
                chain.thisObject.callMethod("clearRecentsTaskLoadPlan")
                log("RecentsCacheFix: cache cleared before getSmartRecentsTaskLoadPlan")
                chain.proceed()
            })
            log("RecentsCacheFix: getSmartRecentsTaskLoadPlan hooked")
        }

        // Fix #2: task filtering — prevent needRemoveTask from dropping tasks
        //   needRemoveTask(GroupedRecentTaskInfoCompat) is the only active overload
        //   (called from getRecentTasksForceIncludingTaskIdIfValid line 375).
        //   Returning false preserves the call chain while stopping all filtering
        //   (canShowOutScreenWithComponent stale validPkgSet + blacklist).
        safeHook("RecentsCacheFix-filter") {
            val amwClass = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.wrapper.ActivityManagerWrapper")
            val groupedClass = param.classLoader.loadClass(
                "com.android.systemui.shared.recents.model.GroupedRecentTaskInfoCompat")
            val needRemove = amwClass.method(
                "needRemoveTask", groupedClass)
            hook(needRemove, replaceResult(false))
            log("RecentsCacheFix: needRemoveTask(GroupedRecentTaskInfoCompat) → false")
        }
    }
}
