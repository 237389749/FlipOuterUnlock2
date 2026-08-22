package com.example.flipunlock.hook.systemui

import android.content.ContextWrapper
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 横屏控制中心磁贴布局宽度固定 → 撑满屏幕宽度（2026-08-22, refMD §43.6.7）。
 *
 * 现象（用户实测）: 横屏状态下磁贴布局宽度固定, 即便磁贴很少/没有也不变化。
 *
 * 根因（3 agent 实锤, flip2-systemuiplugin/b5c1 systemui-plugin 一致）:
 *   flip2 内屏样式版控制中心由 systemui-plugin 插件接管, 宽度/列数全部硬编码:
 *   - MainPanelController.updatePanelWidth()(:610-612): panelWidth =
 *     style==COMPACT ? control_center_universal_3_rows_with_margin_size(256.5dp)
 *     : control_center_universal_4_rows_with_margin_size(**342dp**)——HORIZONTAL/
 *     VERTICAL/WIDE_VERTICAL 共用, 且该 dimens **无 values-land 覆盖** → 横屏仍 342dp
 *   - MainPanelAdapter.updateSpanCount()(:325-328): 列数 COMPACT?3:4 固定
 *   - 无 auto-fit: GridLayoutManager 固定 span 均分, 无按磁贴数量收缩机制
 *   - 横屏唯一差异 = updateUseSeparatedPanels()(:958-960): !getInVerticalMode() →
 *     双面板并排(left+right, 中缝 control_center_horizontal_margin_center=27.4dp,
 *     总宽 342×2+27.4≈711dp 居中)——在 2912px 横屏上显得窄且固定
 *   - 主 APK 侧列数资源化(land infinite_grid=8/num_columns=5)但插件接管后不参与
 *
 * 修复（用户确认目标 ② 横屏撑满屏幕宽度）:
 *   hook MainPanelController.updatePanelWidth() after → style==HORIZONTAL 时
 *   panelWidth = (屏宽 - 中缝)/2（双面板撑满全宽, 保留中缝）;
 *   updateResources 顺序 = updatePanelWidth → updateUseSeparatedPanels →
 *   updatePanelStyle → updatePanelSize → 改 panelWidth 后 updatePanelSize 自然用新值;
 *   竖屏(VERTICAL)分支不动(342dp), 转回竖屏自动恢复。
 *
 * 注入: 路径 A(§43.6.3b)——插件类在宿主 classloader 的【子级】独立 PathClassLoader,
 *   hook PluginFactory.createPluginContext() after 拿 ContextWrapper.classLoader;
 *   插件运行在 com.android.systemui 进程(manifest 无独立进程, §43.6.3b ①)。
 * 开关: persist.flipunlock.ui.qspanelwidth（默认 true）
 */
object QSPanelWidthFixHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "android")

    /** MainPanelController 候选类名: 设备 dex 明文 + jadx 反混淆产物防御。 */
    private val CONTROLLER_CANDIDATES = listOf(
        "miui.systemui.controlcenter.panel.main.MainPanelController",
        "miui.systemui.controlcenter.panel.main.p113qs.MainPanelController",
    )

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.qsPanelWidth) {
            log("QSPanelWidthFix: skip, toggle off")
            return
        }
        val process = currentProcessName()
        if (process != "com.android.systemui") {
            log("QSPanelWidthFix: skip, process=$process")
            return
        }
        log("QSPanelWidthFix: loading for ${param.packageName} (process=$process)")
        val cl = processClassLoader(param.classLoader)

        safeHook("QSPanelWidthFix") {
            // PluginFactory 多路加载(宿主类)
            val factoryCls = sequenceOf(
                runCatching { cl.findClassUp("com.android.systemui.shared.plugins.PluginInstance\$PluginFactory") }.getOrNull(),
                runCatching { param.classLoader.loadClass("com.android.systemui.shared.plugins.PluginInstance\$PluginFactory") }.getOrNull(),
            ).firstNotNullOfOrNull { it }
            if (factoryCls == null) {
                log("QSPanelWidthFix: PluginFactory 找不到, skip")
                return@safeHook
            }
            val createPluginContext = runCatching {
                factoryCls.method("createPluginContext")
            }.getOrNull()
            if (createPluginContext == null) {
                log("QSPanelWidthFix: createPluginContext() 找不到, skip")
                return@safeHook
            }
            var hooked = false
            hook(createPluginContext, after { chain, result ->
                if (hooked) return@after result
                val wrapper = result as? ContextWrapper ?: return@after result
                val pluginLoader = wrapper.classLoader ?: return@after result
                hooked = true
                installHooks(pluginLoader)
                result
            })
            log("QSPanelWidthFix: PluginFactory.createPluginContext hooked")
        }
    }

    private fun installHooks(pluginLoader: ClassLoader) {
        // Style 枚举（判断 HORIZONTAL）
        val styleCls = runCatching {
            pluginLoader.loadClass("miui.systemui.controlcenter.panel.main.MainPanelController\$Style")
        }.getOrNull()
        val horizontalStyle = styleCls?.let {
            runCatching { it.field("HORIZONTAL").get(null) }.getOrNull()
        }
        if (horizontalStyle == null) {
            log("QSPanelWidthFix: Style.HORIZONTAL 找不到, skip")
            return
        }
        // 中缝 dimen id（control_center_horizontal_margin_center）
        val marginResId = runCatching {
            pluginLoader.loadClass("miui.systemui.controlcenter.R\$dimen")
                .field("control_center_horizontal_margin_center").getInt(null)
        }.getOrNull()

        for (candidate in CONTROLLER_CANDIDATES) {
            val cls = runCatching { pluginLoader.loadClass(candidate) }.getOrNull() ?: continue
            val updatePanelWidth = runCatching { cls.method("updatePanelWidth") }.getOrNull()
                ?: run {
                    log("QSPanelWidthFix: $candidate 无 updatePanelWidth(), skip")
                    continue
                }
            hook(updatePanelWidth, after { chain, result ->
                val controller = chain.thisObject ?: return@after result
                // 仅 HORIZONTAL（横屏）生效, 竖屏/VERTICAL 保持原 342dp
                val style = runCatching { controller.callMethod("getStyle") }.getOrNull()
                    ?: return@after result
                if (style != horizontalStyle) return@after result
                val ctx = runCatching {
                    controller.callMethod("getContext") as? android.content.Context
                }.getOrNull() ?: return@after result
                val screenWidth = ctx.resources.displayMetrics.widthPixels
                if (screenWidth <= 0) return@after result
                val margin = marginResId?.let { runCatching { ctx.resources.getDimensionPixelSize(it) }.getOrNull() } ?: 0
                val newWidth = (screenWidth - margin) / 2
                if (newWidth > 0) {
                    controller.setField("panelWidth", newWidth)
                    log("QSPanelWidthFix: 横屏面板宽 $newWidth px (屏宽 $screenWidth, 中缝 $margin) — 撑满")
                }
                result
            })
            log("QSPanelWidthFix: ✓ ${cls.name}.updatePanelWidth hooked")
            return  // 命中一个即够
        }
        log("QSPanelWidthFix: 所有候选类均未命中")
    }
}
