package com.example.flipunlock.hook.identity

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook all device identity detection paths to make the system treat the
 * Mix Flip as a regular phone.
 *
 * ROOT: MiuiMultiDisplayTypeInfo.isFlipDevice() ← persist.sys.multi_display_type == 4
 *   ├── MiuiConfigs.isFlipTinyScreen()     ← isFlipDevice() && density <= 670
 *   ├── MiuiConfigs.isTinyScreen()         ← density <= 670
 *   ├── MiuiConfigs.isFoldableDevice()     ← IS_FOLD || isFlipDevice()
 *   ├── DeviceUtils.isFlipTinyScreen()     ← isFlipDevice() && screenType == 1
 *   ├── DeviceUtils.isFlipDevice()         ← delegates to MiuiMultiDisplayTypeInfo
 *   ├── DeviceHelper.isTinyScreen()        ← detectType()==4 && screenType == 1
 *   ├── DeviceHelper.detectType()          ← returns 4 (FLIP)
 *   ├── miui.os.Build.isFlipDevice()       ← delegates to MiuiMultiDisplayTypeInfo
 *   ├── miuix.os.Build.IS_FLIP             ← static field from same property
 *   ├── miuix.os.Build.IS_FOLD_INSIDE/OUTSIDE ← static fields for foldable detection
 *   └── miuix.os.Build.IS_FOLDABLE         ← static field
 *
 * Wildcard hook: fires on firstPackage only.
 * Excludes: SystemUI (lock screen layout), Sogou (keyboard height).
 */
object DeviceIdentityHook : BaseHook() {
    override val targetPackages = listOf("*")

    override fun hook(param: PackageReadyParam) {
        if (param.packageName in Exclusions.DEVICE_IDENTITY) return
        super.hook(param)
    }

    override fun setupHooks(param: PackageReadyParam) {
        log("DeviceIdentityHook: loading for ${param.packageName}")
        hookRootDeviceType(param)
        hookMiuiBuild(param)
        hookMiuixBuildStatic(param)
        hookDeviceUtils(param)
        hookDeviceHelper(param)
        hookMiuiConfigs(param)
        hookDefensiveStatics(param)
    }

    // ── ROOT: MiuiMultiDisplayTypeInfo ─────────────────────────────────
    // Single source of truth. isFlipDevice() returns true when
    // persist.sys.multi_display_type == 4. Blocking this cascades to
    // miui.os.Build and DeviceUtils which delegate to it.
    private fun hookRootDeviceType(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miui.util.MiuiMultiDisplayTypeInfo")
            hook(cls.method("isFlipDevice"), replaceResult(false))
            hook(cls.method("isFoldDevice"), replaceResult(false))
            log("DeviceIdentity: MiuiMultiDisplayTypeInfo.isFlipDevice/isFoldDevice → false")
        }.onFailure { log("DeviceIdentity: MiuiMultiDisplayTypeInfo not found", it) }
    }

    // ── miui.os.Build.isFlipDevice() ───────────────────────────────────
    private fun hookMiuiBuild(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miui.os.Build")
            hook(cls.method("isFlipDevice"), replaceResult(false))
            log("DeviceIdentity: miui.os.Build.isFlipDevice → false")
        }.onFailure { log("DeviceIdentity: miui.os.Build not found", it) }
    }

    // ── miuix.os.Build static fields ───────────────────────────────────
    // IS_FLIP, IS_FOLD_INSIDE, IS_FOLD_OUTSIDE, IS_FOLDABLE are all
    // initialized at class-load time from persist.sys.multi_display_type.
    // Must clear final modifier before writing.
    private fun hookMiuixBuildStatic(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miuix.os.Build")
            clearStaticFinalBoolean(cls, "IS_FLIP", false)
            clearStaticFinalBoolean(cls, "IS_FOLD_INSIDE", false)
            clearStaticFinalBoolean(cls, "IS_FOLD_OUTSIDE", false)
            clearStaticFinalBoolean(cls, "IS_FOLDABLE", false)
            log("DeviceIdentity: miuix.os.Build static fields cleared")
        }.onFailure { log("DeviceIdentity: miuix.os.Build not found", it) }
    }

    // ── miuix.device.DeviceUtils ───────────────────────────────────────
    private fun hookDeviceUtils(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miuix.device.DeviceUtils")
            hook(cls.method("isFlipTinyScreen", android.content.Context::class.java), replaceResult(false))
            hook(cls.method("isFlipDevice"), replaceResult(false))
            log("DeviceIdentity: DeviceUtils.isFlipTinyScreen/isFlipDevice → false")
        }.onFailure { log("DeviceIdentity: DeviceUtils not found", it) }
    }

    // ── miuix.os.DeviceHelper ──────────────────────────────────────────
    // detectType() returns 4 (FLIP) on this device. Force to 1 (PHONE).
    // isTinyScreen() checks detectType()==4 && screenType==1.
    private fun hookDeviceHelper(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miuix.os.DeviceHelper")
            hook(cls.method("isTinyScreen", android.content.Context::class.java), replaceResult(false))
            hook(cls.method("detectType", android.content.Context::class.java), replaceResult(1))
            log("DeviceIdentity: DeviceHelper.isTinyScreen → false, detectType → 1")
        }.onFailure { log("DeviceIdentity: DeviceHelper not found", it) }
    }

    // ── miui.util.MiuiConfigs ──────────────────────────────────────────
    private fun hookMiuiConfigs(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miui.util.MiuiConfigs")
            hook(cls.method("isFoldableDevice"), replaceResult(false))
            hook(cls.method("isFlipTinyScreen", android.content.Context::class.java), replaceResult(false))
            hook(cls.method("isTinyScreen", android.content.Context::class.java), replaceResult(false))
            log("DeviceIdentity: MiuiConfigs fold/tiny screen methods → false")
        }.onFailure { log("DeviceIdentity: MiuiConfigs not found", it) }
    }

    // ── Defensive: clear remaining fold/notch static flags ─────────────
    // These provide defense-in-depth against foldable/tablet/notch paths.
    // DeviceFeature.IS_FOLD_DEVICE may have been set before our hook fires.
    private fun hookDefensiveStatics(param: PackageReadyParam) {
        clearStaticFinalField(param, "miui.util.MiuiConfigs", "IS_FOLD", false)
        clearStaticFinalField(param, "miui.util.MiuiConfigs", "IS_NOTCH", false)
        clearStaticFinalField(param, "miui.util.MiuiConfigs", "IS_PAD", false)
        clearStaticFinalField(param, "miui.os.DeviceFeature", "IS_FOLD_DEVICE", false)
    }

    // ── Utility: clear a static final boolean field via reflection ─────
    @Suppress("BanDiscouragedJavaApi")
    private fun clearStaticFinalField(
        param: PackageReadyParam,
        className: String,
        fieldName: String,
        value: Boolean,
    ) {
        runCatching {
            val cls = param.classLoader.loadClass(className)
            clearStaticFinalBoolean(cls, fieldName, value)
        }.onFailure { /* class or field may not exist in all processes */ }
    }

    @Suppress("BanDiscouragedJavaApi")
    private fun clearStaticFinalBoolean(cls: Class<*>, fieldName: String, value: Boolean) {
        val field = cls.field(fieldName)
        runCatching {
            val modifiersField = java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
            modifiersField.isAccessible = true
            modifiersField.setInt(field, field.modifiers and 0xFFFFFFEF.toInt())
        }
        field.setBoolean(null, value)
        log("DeviceIdentity: ${cls.name}.$fieldName = $value")
    }
}
