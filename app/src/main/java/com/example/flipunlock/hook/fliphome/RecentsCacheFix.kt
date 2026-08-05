package com.example.flipunlock.hook.fliphome

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix: FlipHome recents view sometimes doesn't show all recent tasks.
 *
 * Root cause: RecentsModel caches the RecentsTaskLoadPlan (mRecentsTaskLoadPlan).
 * getSmartRecentsTaskLoadPlan() reuses the cached plan and only calls updateTasks()
 * (blur/lock state refresh) — it does NOT reload the task list from the system.
 * If new tasks were created after the background preload, they won't appear.
 *
 * Previous approach (failed): hook getTaskLoadPlan() → return null.
 *   getTaskLoadPlan() is a trivial getter (return mRecentsTaskLoadPlan) that R8
 *   inlines into callers — the hook never fires (Pitfall #7: R8 inlining).
 *
 * New approach: hook getSmartRecentsTaskLoadPlan(Context, int) — the complex
 *   orchestrator method that won't be inlined. Clear the cache via
 *   clearRecentsTaskLoadPlan() before proceeding, forcing a fresh plan with
 *   preloadTasks() (full system task list reload).
 *
 * Process: com.miui.fliphome
 */
object RecentsCacheFix : BaseHook() {

    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        safeHook("RecentsCacheFix") {
            val recentsModelClass = param.classLoader.loadClass(
                "com.miui.fliphome.recents.RecentsModel")
            val getSmartPlan = recentsModelClass.method(
                "getSmartRecentsTaskLoadPlan",
                android.content.Context::class.java,
                Int::class.javaPrimitiveType!!)
            hook(getSmartPlan, Hooker { chain ->
                // Clear cached plan so the original method takes the null branch:
                //   taskLoadPlan == null → createLoadPlan() → preloadTasks() (full reload)
                chain.thisObject.callMethod("clearRecentsTaskLoadPlan")
                log("RecentsCacheFix: cache cleared before getSmartRecentsTaskLoadPlan")
                chain.proceed()
            })
            log("RecentsCacheFix: getSmartRecentsTaskLoadPlan hooked → fresh load every time")
        }
    }
}
