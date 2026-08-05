package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Force every app to fill the whole outer screen by disabling MIUI's flip
 * "size compat" letterbox.
 *
 * Why it matters: even after the display cutout is removed, MIUI still
 * letterboxes apps that don't declare the `miui.supportFlipFullScreen`
 * manifest metadata to a fixed aspect ratio (~1.72). The ActivityRecord is
 * marked `letterboxReason=MIUI_SIZE_COMPAT_MODE` and the app is squeezed into
 * a narrower compat-bounds rect, leaving a black band. This is a SEPARATE
 * mechanism from the cutout — removing the cutout does not clear it.
 *
 * Logic chain (refMD: Gesture_Widget_Overlay.md §SystemServicesHook,
 *             DisplayCutout.md §BoundsCompatUtils):
 *
 *   app lacks miui.supportFlipFullScreen metadata
 *     → WindowManagerServiceImpl.getFullScreenValue(PackageItemInfo)  ← hook #3
 *       → BoundsCompatUtils.getFlipCompatModeByApp(ATMS, pkg)         ← hook #1
 *       → BoundsCompatUtils.getFlipCompatModeByActivity(ActivityRecord) ← hook #2
 *         → compat mode != 0 → MIUI_SIZE_COMPAT_MODE letterbox
 *           → mCompatBounds (fixed aspect ratio) → app not fullscreen
 *
 * All three return 0 = "fullscreen, no compat letterbox". Hooking the two
 * getFlipCompatMode* entry points covers both the app-level and the
 * activity-level resolution paths; getFullScreenValue is the metadata reader
 * that feeds them, forced for good measure (matches the proven old-project
 * SystemServicesHook combination).
 *
 * Note: on the inner (normal) screen apps already resolve to fullscreen, so
 * forcing mode 0 there is a no-op — this only changes outer-screen behavior.
 *
 * Defense (#4): compat mode 0 makes BoundsCompatController.canUseFixedAspectRatio()
 * return false, which already skips the whole scale computation (mCurrentScale
 * stays 1.0f). BUT canUseFixedAspectRatio() returns true FIRST if
 * ActivityTaskManagerServiceStub.shouldApplyAspectRatio(ar) is true (a per-app
 * fixed-aspect-ratio override), bypassing the compat-mode gate. To close that
 * leak we also force ActivityTaskManagerServiceImpl.getGlobalScale(ActivityRecord)
 * → 1.0f (matches MixFlipMod's NO_SCALE). getGlobalScale is only consumed inside
 * the canUseFixedAspectRatio block, so on the inner screen this is a no-op too.
 *
 * Toggle: persist.flipunlock.display.fullscreen (default true)
 * Process: system_server (#1-#4) + app processes (#5-#6)
 */
object AppFullscreen {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayFullscreen) {
            log("AppFullscreen: DISABLED by persist.flipunlock.display.fullscreen")
            return
        }
        log("AppFullscreen: setting up")
        safeHook("AppFullscreen") {
            hookFlipCompatModeByApp(param.classLoader)
            hookFlipCompatModeByActivity(param.classLoader)
            hookFullScreenValue(param.classLoader)
            hookGlobalScale(param.classLoader)
        }
    }

    // ── #1 BoundsCompatUtils.getFlipCompatModeByApp(ATMS, String) → 0 ──
    private fun hookFlipCompatModeByApp(classLoader: ClassLoader) {
        runCatching {
            val boundsCompatUtils = classLoader.loadClass(
                "com.android.server.wm.BoundsCompatUtils")
            val atmsClass = classLoader.loadClass(
                "android.app.ActivityTaskManagerService")
            val method = boundsCompatUtils.method(
                "getFlipCompatModeByApp", atmsClass, String::class.java)
            hook(method, replaceResult(0))
            log("AppFullscreen: getFlipCompatModeByApp → 0")
        }.onFailure { log("AppFullscreen: getFlipCompatModeByApp hook failed", it) }
    }

    // ── #2 BoundsCompatUtils.getFlipCompatModeByActivity(ActivityRecord) → 0 ──
    private fun hookFlipCompatModeByActivity(classLoader: ClassLoader) {
        runCatching {
            val boundsCompatUtils = classLoader.loadClass(
                "com.android.server.wm.BoundsCompatUtils")
            val activityRecordClass = classLoader.loadClass(
                "com.android.server.wm.ActivityRecord")
            val method = boundsCompatUtils.method(
                "getFlipCompatModeByActivity", activityRecordClass)
            hook(method, replaceResult(0))
            log("AppFullscreen: getFlipCompatModeByActivity → 0")
        }.onFailure { log("AppFullscreen: getFlipCompatModeByActivity hook failed", it) }
    }

    // ── #3 WindowManagerServiceImpl.getFullScreenValue(PackageItemInfo) → 0 ──
    private fun hookFullScreenValue(classLoader: ClassLoader) {
        runCatching {
            val wmsImpl = classLoader.loadClass(
                "com.android.server.wm.WindowManagerServiceImpl")
            val packageItemInfoClass = classLoader.loadClass(
                "android.content.pm.PackageItemInfo")
            val method = wmsImpl.method(
                "getFullScreenValue", packageItemInfoClass)
            hook(method, replaceResult(0))
            log("AppFullscreen: getFullScreenValue → 0")
        }.onFailure { log("AppFullscreen: getFullScreenValue hook failed", it) }
    }

    // ── #4 ActivityTaskManagerServiceImpl.getGlobalScale(ActivityRecord) → 1.0f (defense) ──
    private fun hookGlobalScale(classLoader: ClassLoader) {
        runCatching {
            val atmsImpl = classLoader.loadClass(
                "com.android.server.wm.ActivityTaskManagerServiceImpl")
            val activityRecordClass = classLoader.loadClass(
                "com.android.server.wm.ActivityRecord")
            val method = atmsImpl.method(
                "getGlobalScale", activityRecordClass)
            hook(method, replaceResult(1.0f))
            log("AppFullscreen: getGlobalScale → 1.0f (no scale)")
        }.onFailure { log("AppFullscreen: getGlobalScale hook failed", it) }
    }

    // ── App-process hooks (#5-#6): disable MIUI size-compat inside the app ──
    // system_server hooks (#1-#4) tell WMS "this app wants fullscreen".
    // These app-side hooks tell the app itself "you are NOT in size-compat mode",
    // preventing applyViewLocation() view shifts and DecorView inset changes
    // that cause black status bar areas.

    /** Called from onPackageReady for app processes. */
    fun hookApp(param: PackageReadyParam) {
        if (!Config.displayFullscreen) return
        if (param.packageName in Exclusions.DEVICE_IDENTITY) return
        safeHook("AppFullscreen") {
            hookSizeCompatScaleMode(param.classLoader)
            hookSizeCompatBounds(param.classLoader)
        }
    }

    // ── #5 ActivityThreadImpl.inMiuiSizeCompatScaleMode() → false ──
    private fun hookSizeCompatScaleMode(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("android.app.ActivityThreadImpl")
            val method = cls.method("inMiuiSizeCompatScaleMode")
            hook(method, replaceResult(false))
            log("AppFullscreen: inMiuiSizeCompatScaleMode → false")
        }.onFailure { log("AppFullscreen: inMiuiSizeCompatScaleMode hook failed", it) }
    }

    // ── #6 ActivityThreadImpl.getSizeCompatBounds() → null ──
    private fun hookSizeCompatBounds(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("android.app.ActivityThreadImpl")
            val method = cls.method("getSizeCompatBounds")
            hook(method, replaceResult(null))
            log("AppFullscreen: getSizeCompatBounds → null")
        }.onFailure { log("AppFullscreen: getSizeCompatBounds hook failed", it) }
    }
}
