package com.example.flipunlock.hook.identity

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook MiuiMultiDisplayTypeInfo.isFlipDevice() → false.
 *
 * ROOT: isFlipDevice() ← persist.sys.multi_display_type == 4
 *   This is the single source of truth. All other identity checks
 *   (miui.os.Build, DeviceUtils, MiuiConfigs, etc.) delegate to it
 *   or read the same system property.
 *
 * Validated by FlipOuterUnlock_262 elimination testing (2026-08-07):
 *   - isFlipDevice→false ALONE solves toast centering
 *   - isFoldDevice hook NOT needed (commented out in 262)
 *   - Additional hooks (miuix.os.Build fields, DeviceHelper, etc.)
 *     are untested and may cause side effects.
 *
 * Wildcard hook: fires on firstPackage only.
 * Exclusions (restored from validated 262 config, 2026-08-08):
 *   - com.android.systemui  : TinyKeyguardPanelViewController NPE-crashes
 *     KeyguardService when isFlipDevice→false (HyperOS3 firmware b5c1e89).
 *   - com.miui.fliphome     : outer launcher init needs real identity.
 *   - sogou IME             : keyboard height on outer screen.
 * Each exclusion is togglable via persist.flipunlock.identity.exclude.*
 */
object DeviceIdentityHook : BaseHook() {
    override val targetPackages = listOf("*")

    @Volatile private var hooksInstalled = false

    override fun hook(param: PackageReadyParam) {
        val pkg = param.packageName
        val excluded = when (pkg) {
            "com.android.systemui" -> Config.identityExcludeSystemUi
            "com.miui.fliphome" -> Config.identityExcludeFliphome
            "com.sohu.inputmethod.sogou.xiaomi" -> Config.identityExcludeSogou
            else -> false
        }
        if (excluded) {
            log("DeviceIdentityHook: $pkg excluded (keeps real flip identity)")
            return
        }
        if (hooksInstalled) return
        hooksInstalled = true

        super.hook(param)
    }

    override fun setupHooks(param: PackageReadyParam) {
        log("DeviceIdentityHook: loading for ${param.packageName}")
        safeHook("DeviceIdentityHook") {
            val cls = param.classLoader.loadClass("miui.util.MiuiMultiDisplayTypeInfo")
            runCatching {
                val method = cls.method("isFlipDevice")
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked MiuiMultiDisplayTypeInfo.isFlipDevice")
            }
            // isFoldDevice — NOT hooked (validated by 262 elimination: not needed)
            // runCatching {
            //     val method = cls.method("isFoldDevice")
            //     hook(method, replaceResult(false))
            //     log("DeviceIdentity: blocked MiuiMultiDisplayTypeInfo.isFoldDevice")
            // }
        }
    }
}
