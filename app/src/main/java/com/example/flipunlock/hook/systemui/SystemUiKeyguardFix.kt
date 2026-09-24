package com.example.flipunlock.hook.systemui

import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * SystemUI 锁屏面板守护（历史 + 2026-09-24 修复）。
 *
 * ── 原始用途（b5c1e89 时代）──
 *   providesTinyKeyguardViewPager(NotificationShadeWindowView) 按 isFlipDevice() 分支：
 *   false → 返回**空** TinyKeyguardPanelView（不 inflate）；true → ViewStub.inflate() 后返回真 panel。
 *   当时 R8 合并去掉了 Dummy 保护（Dagger 无条件构造 TinyKeyguardPanelViewControllerImpl），
 *   isFlipDevice→false 时空 view 会让 ViewController 的 findViewById(
 *   tiny_keyguard_templates_viewpager) 得 null → getClass() NPE → KeyguardService 崩溃环。
 *   ⇒ 当年对策 = 强行走 true 分支（inflate + 返回真 view）。
 *
 * ── 2026-09-24 修复：该「强 inflate」在现版国际版上反而制造黑层 ──
 *   现版 `FlipKeyguardModule_ProvideTinyKeyguardPanelViewControllerFactory` 恢复为
 *   `isFlipDevice() ? Impl : Dummy`（**Dummy 保护回来了**）→ 属性 1 下是
 *   `TinyKeyguardPanelViewControllerDummy`（空类，所有方法走接口 default 空体，
 *   **从不调用 updateVisibility()**）。
 *   而强 inflate 的 panel 内含全屏黑背景：
 *     <ViewPager2 android:background="@android:color/black" match_parent>  (tiny_keyguard_panel.xml)
 *   层级为 shade_background → **黑层** → status_bar_expanded/keyguard_root_view(内容)
 *   → 黑层常驻 VISIBLE、盖住壁纸，但内容在其上 ⇒ **「内容在、只有背景黑」**。
 *
 *   实机实证（flip1 ruyi_global, 2026-09-24, refMD §44.11）：
 *     `persist.flipunlock.ui.keyguardfix=false` → 控制中心/通知中心**恢复壁纸**，
 *     且**无 SystemUI 崩溃环** ⇒ 证明 Dummy 保护存在、强 inflate 已属多余且有害。
 *
 *   ⇒ 现策略：**探测到 Dummy 即整个 SKIP**（不 inflate → 从源头不产生黑层）；
 *     仅当 Dummy 真不存在（未来 R8 再合并掉）时才保留强 inflate 兜底。
 *
 * ⚠️ 注意：锁屏本身的「没有壁纸」是**另一条独立链**（`SysuiColorExtractor.lock=null` +
 *    壁纸层 alpha=0），与本 hook 无关，见 refMD §44.12。
 *
 * Process: com.android.systemui
 * Toggle : persist.flipunlock.ui.keyguardfix（默认 true）
 */
object SystemUiKeyguardFix : BaseHook() {
    override val targetPackages = listOf("com.android.systemui")

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.keyguardFix) {
            log("SystemUiKeyguardFix: DISABLED by persist.flipunlock.ui.keyguardfix")
            return
        }
        // 2026-08-14: flip2 有 Dummy 保护(§38.2), 属性1下走 Dummy 不崩, 不需要强制 inflate;
        // 且 flip2 SystemUI 的 provider 类名是 AbstractC4516x63c84e27(flip1 是 C4499), 类名漂移。
        // flip1 专用。误作用在 flip2 上可能破坏锁屏(背景黑/双人脸图标)。
        if (isFlip2Device()) {
            log("SystemUiKeyguardFix: SKIP (flip2 有 Dummy 保护, 不需要此守护)")
            return
        }
        log("SystemUiKeyguardFix: loading for ${param.packageName}")
        // ── 2026-09-24 修复：属性 1 下「强 inflate 反而制造黑层」──────────────────
        // 属性 1 → isFlipDevice()==false → Dagger 产出 TinyKeyguardPanelViewControllerDummy
        // （空类，所有方法走接口 default 空体，**从不调用 updateVisibility()**）。
        // 而本 hook 原先强 inflate 的 panel 内含
        //   <ViewPager2 android:background="@android:color/black" match_parent>（tiny_keyguard_panel.xml）
        // → 黑层常驻 VISIBLE，盖住 shade_background（层级：shade_background → 黑层 → 内容）
        // → **控制中心/通知中心背景黑**（内容还在，只背景黑）。
        //
        // 实机实证（flip1 ruyi_global, 2026-09-24, refMD §44.11）：
        //   keyguardfix=false 时控制中心/通知中心**恢复壁纸**，且**无 SystemUI 崩溃环**
        //   → 证明国际版 Dummy 保护存在、本 hook 属多余且有害。
        //
        // 故：探测到 Dummy 即整个 SKIP（不 inflate → 从源头不产生黑层）。
        // 注意：必须用 processClassLoader()——pkg=android 回调时 param.classLoader 是系统框架、
        //       不含 APK 类（HookUtils 注释已写明），直接用会误判 Dummy 不存在。
        val cl = processClassLoader(param.classLoader)
        val dummyName = "com.android.keyguard.tinyPanel.TinyKeyguardPanelViewControllerDummy"
        val hasDummy = runCatching { cl.loadClass(dummyName) }.isSuccess
        if (hasDummy) {
            log("SystemUiKeyguardFix: SKIP (Dummy 保护存在 → 不 inflate, 避免全屏黑层常驻)")
            return
        }
        log("SystemUiKeyguardFix: Dummy 不存在(R8 合并?) → 保留强 inflate 兜底")
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
