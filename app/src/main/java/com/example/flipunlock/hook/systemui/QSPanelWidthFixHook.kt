package com.example.flipunlock.hook.systemui

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.view.Surface
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 横屏控制中心磁贴布局宽度固定 → 双面板撑满屏幕宽度（2026-08-22 初版, refMD §43.6.7;
 * 2026-09-25 国际版诊断版: 修"首个 createPluginContext 不是控制中心插件"导致的静默锁定 + 补全日志）。
 *
 * 现象（用户实测, 2026-08-22 → 2026-09-25 仍复现）: 横屏下磁贴布局宽度固定, 即便磁贴很少也不变;
 * 右侧固定磁贴(亮度/音量等 4 项)被挤出屏幕外。
 *
 * 根因（3 agent 实锤, flip2-systemuiplugin/b5c1 systemui-plugin 一致; refMD §43.6.7）:
 *   flip 内屏样式版控制中心由 systemui-plugin 插件接管, 宽度/列数全部硬编码:
 *   - MainPanelController.updatePanelWidth()(:610-612): panelWidth =
 *     style==COMPACT ? control_center_universal_3_rows_with_margin_size(256.5dp)
 *     : control_center_universal_4_rows_with_margin_size(**342dp**)——HORIZONTAL/
 *     VERTICAL/WIDE_VERTICAL 共用, 且该 dimens **无 values-land 覆盖** → 横屏仍 342dp
 *   - MainPanelAdapter.updateSpanCount()(:325-328): 列数 COMPACT?3:4 固定
 *   - 无 auto-fit: GridLayoutManager 固定 span 均分, 无按磁贴数量收缩/撑开机制
 *   - **横屏唯一结构差异** = updateUseSeparatedPanels()(:958-960): !getInVerticalMode() →
 *     双面板并排(left 磁贴 + right 固定磁贴, 中缝 control_center_horizontal_margin_center=27.4dp,
 *     总宽 342×2+27.4≈711dp)——外屏横屏宽仅 1392px≈398dp(@560dpi) → 右侧面板整个被挤出屏外
 *   - 主 APK 侧列数资源化(land infinite_grid=8/num_columns=5)但插件接管后不参与
 *
 * 国际版(ruyi_global)本机核实（2026-09-25, res/FlipRes_global 反编译）:
 *   - 宿主侧**改不动宽度**: ControlCenterContainerController.onContentAttached(:131-137) 把插件
 *     内容视图以 MATCH_PARENT 加进 content_container; 宿主只读 getPanelBorder() 算通知栏/控制中心
 *     滑动切换距离(ShadeSwitchControllerImpl:408-456) → 面板几何完全由插件决定, 必须走插件 hook
 *   - 宿主 res 只有 control_center_universal_4_rows_with_margin_size=342dp 与
 *     control_center_horizontal_margin_center=27.4dp, **均无 values-land 覆盖**（与插件侧一致）
 *   - PluginContextWrapper extends ContextWrapper(PluginActionManager:78-102) → 路径 A 取
 *     wrapper.classLoader 成立; 插件类名仍是 jadx 明文 miui.systemui.controlcenter.panel.main.*
 *   - ⚠️ 插件 APK(/product/app/MIUISystemUIPlugin/MIUISystemUIPlugin.apk) **尚未反编译**,
 *     上面行号/字段名来自国内版插件 → 本次先补日志, 装机日志可确认国际版是否同构
 *
 * 修复（用户确认目标 ② 横屏撑满屏幕宽度）:
 *   hook MainPanelController.updatePanelWidth() after → **横屏**且双面板总宽放不下时
 *   panelWidth = (屏宽 - 中缝)/2（两面板+中缝正好铺满, 保留中缝）;
 *   updateResources 顺序 = updatePanelWidth → updateUseSeparatedPanels →
 *   updatePanelStyle → updatePanelSize → 改 panelWidth 后 updatePanelSize 自然用新值;
 *   竖屏不动(单面板 342dp), 转回竖屏自动恢复。
 *   判定条件用 **屏宽>屏高**（不是 style==HORIZONTAL）: refMD 实锤"横屏唯一结构差异 = 双面板",
 *   而样式在属性层/国际版下可能是 COMPACT(3 列) 或 HORIZONTAL(4 列) —— 初版只认 HORIZONTAL,
 *   若实际是 COMPACT 则整个 hook 静默跳过(用户"固定三列"现象吻合 COMPACT)。
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

    private const val STYLE_CLASS =
        "miui.systemui.controlcenter.panel.main.MainPanelController\$Style"

    /** 竖屏日志只打一次, 避免 updateResources 反复刷屏。 */
    @Volatile
    private var portraitLogged = false

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
                val component = runCatching {
                    chain.thisObject?.getField("mComponentName") as? ComponentName
                }.getOrNull()
                // ⚠️ 初版缺陷: 这里先 hooked=true 再 installHooks, 而 SystemUI 进程会为多个插件
                //    (手电筒/磁贴/通知等, 未必都来自 miui.systemui.plugin) 调 createPluginContext
                //    → 首个若不含控制中心类, 锁定后永远装不上且无日志。改: **命中类才锁定**。
                if (installHooks(pluginLoader)) {
                    hooked = true
                    log("QSPanelWidthFix: 插件 classloader 命中 (component=$component)")
                } else {
                    log("QSPanelWidthFix: component=$component 无控制中心类, 等下一个插件")
                }
                result
            })
            log("QSPanelWidthFix: PluginFactory.createPluginContext hooked")
        }
    }

    /** 命中插件类并装好 hook 返回 true; 不是控制中心插件返回 false（继续等下一个）。 */
    private fun installHooks(pluginLoader: ClassLoader): Boolean {
        // Style 枚举（日志用: 确认国际版样式是 COMPACT 还是 HORIZONTAL）
        val styleCls = runCatching { pluginLoader.loadClass(STYLE_CLASS) }.getOrNull() ?: return false
        val styleFields = styleCls.enumConstants?.joinToString(",") { it.toString() } ?: "?"

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
                val style = runCatching { controller.callMethod("getStyle") }.getOrNull()
                val ctx = runCatching { controller.callMethod("getContext") as? Context }.getOrNull()
                if (ctx == null) {
                    log("QSPanelWidthFix: getContext() 取不到, skip")
                    return@after result
                }
                val dm = ctx.resources.displayMetrics
                // 横屏判定双信号: Resources 未刷新时 dm 仍可能是竖屏(refMD §43.6 已知坑),
                // 故叠加 Display.rotation(真实物理方向)。
                val rotation = runCatching { ctx.display?.rotation }.getOrNull()
                val landscape = dm.widthPixels > dm.heightPixels ||
                    rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
                val old = runCatching { controller.getField("panelWidth") as? Int }.getOrNull() ?: -1
                // 竖屏(单面板): 保持插件原值
                if (!landscape) {
                    if (!portraitLogged) {
                        portraitLogged = true
                        log("QSPanelWidthFix: 竖屏不动 panelWidth=$old style=$style 屏 ${dm.widthPixels}x${dm.heightPixels} rotation=$rotation")
                    }
                    return@after result
                }
                val margin = marginResId
                    ?.let { runCatching { ctx.resources.getDimensionPixelSize(it) }.getOrNull() } ?: 0
                // 双面板(2×panelWidth+中缝)已经放得下 → 不动, 避免误缩窄
                if (old <= 0 || old * 2 + margin <= dm.widthPixels) {
                    log("QSPanelWidthFix: 横屏放得下, 不动 (panelWidth=$old 屏宽=${dm.widthPixels} 中缝=$margin style=$style rotation=$rotation)")
                    return@after result
                }
                val newWidth = (dm.widthPixels - margin) / 2
                if (newWidth > 0) {
                    runCatching { controller.setField("panelWidth", newWidth) }
                        .onSuccess {
                            log("QSPanelWidthFix: 横屏面板宽 $old → $newWidth px " +
                                "(屏 ${dm.widthPixels}x${dm.heightPixels} 中缝=$margin style=$style rotation=$rotation) — 双面板撑满")
                        }
                        .onFailure { log("QSPanelWidthFix: setField(panelWidth) 失败: ${it.message}") }
                }
                result
            })
            log("QSPanelWidthFix: ✓ ${cls.name}.updatePanelWidth hooked (Style 枚举=$styleFields)")
            return true
        }
        log("QSPanelWidthFix: 插件有 Style 类但无候选 MainPanelController, 等下一个插件")
        return false
    }
}
