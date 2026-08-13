package com.example.flipunlock.hook.systemui

import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix SystemUI crash when isFlipDevice→false is applied to SystemUI (b5c1e89).
 *
 * Root cause (refMD §38.1/§38.2):
 *   providesTinyKeyguardViewPager(NotificationShadeWindowView) branches on
 *   isFlipDevice(): false → returns EMPTY TinyKeyguardPanelView (no inflate);
 *   true → ViewStub.inflate() + returns the real panel view.
 *   b5c1e89's R8 merge removed the Dummy protection (Dagger now unconditionally
 *   constructs TinyKeyguardPanelViewController), so with isFlipDevice→false the
 *   ViewController receives the EMPTY view → findViewById(
 *   tiny_keyguard_templates_viewpager) null → getClass() NPE → KeyguardService
 *   crash loop (SystemUI restart loop).
 *
 * Fix: force the isFlipDevice()==true branch — inflate the ViewStub and return
 * the real view, regardless of isFlipDevice. This replicates the normal
 * flip-device path (and what common/flip2 SystemUI achieve via Dummy).
 *
 * Note: target class real name is
 * `ShadeViewProviderModule_Companion_ProvidesTinyKeyguardViewPagerFactory`
 * (jadx renames it to AbstractC4499x63c84e27 — that alias does NOT exist in
 * the dex). Method name providesTinyKeyguardViewPager is stable; the class
 * name may still drift on firmware upgrade (fallback candidates kept).
 *
 * Process: com.android.systemui
 * Toggle: tied to DeviceIdentityHook (this fix exists only because
 *         isFlipDevice→false is applied to SystemUI).
 */
object SystemUiKeyguardFix : BaseHook() {
    override val targetPackages = listOf("com.android.systemui")

    override fun setupHooks(param: PackageReadyParam) {
        log("SystemUiKeyguardFix: loading for ${param.packageName}")
        safeHook("SystemUiKeyguardFix") {
            // 真实类名（jadx renamed 注释还原）：ShadeViewProviderModule_Companion_...
            // 注意：jadx 输出 AbstractC4499x63c84e27 是反混淆重命名，dex 里不存在！
            // R8 名（AbstractC4499x63c84e27）仅供调试提示，保留在候选里兜底。
            val candidates = listOf(
                "com.android.systemui.shade.ShadeViewProviderModule_Companion_ProvidesTinyKeyguardViewPagerFactory",
                "com.android.systemui.shade.AbstractC4499x63c84e27",
            )
            val cls = candidates.firstNotNullOfOrNull { name ->
                runCatching { param.classLoader.loadClass(name) }.getOrNull()
            }
            if (cls == null) {
                log("SystemUiKeyguardFix: provider class not found, tried $candidates (R8 drift?)")
                return@safeHook
            }
            val shadeViewClass = param.classLoader.loadClass(
                "com.android.systemui.shade.NotificationShadeWindowView")
            val method = cls.method("providesTinyKeyguardViewPager", shadeViewClass)

            hook(method) { chain ->
                val shadeView = chain.args[0] as? ViewGroup
                    ?: return@hook chain.proceed()
                // Force the isFlipDevice()==true branch: inflate stub, return real view.
                val stubId = shadeView.resources.getIdentifier(
                    "tiny_keyguard_panel_stub", "id", "com.android.systemui")
                if (stubId != 0) {
                    val stub = shadeView.findViewById<View>(stubId)
                    if (stub is ViewStub) {
                        log("SystemUiKeyguardFix: inflating tiny_keyguard_panel_stub")
                        stub.inflate()
                    }
                }
                val viewId = shadeView.resources.getIdentifier(
                    "tiny_keyguard_panel_view", "id", "com.android.systemui")
                val view = if (viewId != 0) shadeView.findViewById<View>(viewId) else null
                if (view != null) {
                    log("SystemUiKeyguardFix: returning real tiny_keyguard_panel_view")
                    view
                } else {
                    // Defense: fall back to original behavior if inflate failed.
                    chain.proceed()
                }
            }
            log("SystemUiKeyguardFix: ✓ providesTinyKeyguardViewPager → forced true branch")
        }
    }
}
