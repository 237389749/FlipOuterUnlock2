package com.example.flipunlock.hook.systemui

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.view.Surface
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 横屏控制中心"右侧固定磁贴被挤出屏外" → 双面板放不下时退回单面板并撑满屏宽
 * （2026-08-22 初版"切两半"; 2026-09-25 读插件反编译 + 实机视图树 dump 后重写为 v3）。
 *
 * ── 现象（flip1 国际版 ruyi_global 实机复现, 2026-09-30）──
 * 外屏(display 0, 1208x1392@520dpi)旋转到横屏后打开控制中心:
 * 顶部只剩几个磁贴、右侧 WiFi/媒体/亮度/音量等固定磁贴被切在屏幕右缘外。
 * 实测视图树(修复前): 左面板 `main_panel 0,0-1052,1208` + 右面板 `main_panel 1136,0-2188,1208`
 *   → 两面板总宽 2188px, 而屏宽仅 1392px → 右面板只有左边缘 256px 可见。
 *
 * ── 根因（插件 miui.systemui.plugin 反编译实锤, /product/app/MIUISystemUIPlugin）──
 * `MainPanelController.updateResources()`(:897-905):
 *   updatePanelWidth → updateUseSeparatedPanels → updatePanelStyle → updatePanelSize
 * - `updatePanelWidth()`(:559-560): panelWidth = style==COMPACT ? dimen(3_rows) : dimen(**4_rows**)
 *   = `control_center_universal_4_rows_with_margin_size` = **1052px**(323.7dp) —— 与横竖屏无关
 * - `updateUseSeparatedPanels()`(:907-909): `setUseSeparatedPanels(!getInVerticalMode(ctx))`
 *   → **横屏(orientation==2) 一律双面板**; `setUseSeparatedPanels`(:506-519) 把 leftMainPanel
 *   加回容器并设 panelMargin = `control_center_horizontal_margin_center`(84px)
 * - `getPanelContainerWidth()`(:665-667) = separated ? panelWidth*2+margin : panelWidth
 * - `CommonUtils.getInVerticalMode(ctx)`(:542-545) = getForceVertical() || orientation==1
 *   (`getForceVertical()` = IS_TABLET || (IS_FOLD && USING_LARGE_SCREEN), flip 恒 false)
 * ⇒ 双面板总宽 = 1052*2+84 = **2188px**; 该设计只在内屏横屏(1080x2340 → 横屏宽 2340)放得下。
 *   外屏横屏宽 1392px 放不下 → 右面板(承载 WiFi/流量卡/媒体/亮度/音量等固定项)被挤出屏外。
 *
 * ── 为什么不是"把两个面板各切一半"（v2 实测否决）──
 * v2 只改 `panelWidth`(=654px/面板) 实测: 面板框对了, 但插件内部大量尺寸是**固定 dimen/绑定期算好**的:
 * - 磁贴格宽跟随面板(654/4=163 ✓), 但磁贴高度仍是旧值 263 → 圆形磁贴互相重叠
 * - 亮度/音量滑块宽固定 220px(`ToggleSliderViewHolder`:375-382) > 新格宽 163 → 溢出面板
 * - 媒体卡宽 = 绑定期按旧面板算的 483 → 与卡片列(283)不一致
 * ⇒ 逐项去缩放插件的固定尺寸不可维护; 真正与插件设计一致的做法是"**放不下就别分栏**"。
 *
 * ── v3 修复（上游, 单点）──
 * hook `miui.systemui.util.CommonUtils.getInVerticalMode(Context)` → 满足以下条件时返回 true:
 *   横屏(dm 或 Display.rotation) 且 `2*panelWidth + 中缝 > 屏宽`（双面板确实放不下）
 * 这一个开关即可让插件全套逻辑退回"竖屏版式"（= 外屏竖屏时用户已熟悉的单面板版式）:
 * - `updateUseSeparatedPanels` → separated=false（左面板从容器移除）
 * - `MainPanelContentDistributor.distributePanels` 默认值 `!getInVerticalMode()` → false
 *   → **全部内容进同一面板**(:183-195)，不再分左右
 * - `SecondaryPanelControllerBase.updateContainerConstraint`(:179) / 亮度音量内部版式
 *   → 走竖屏分支（否则二级面板会按"面板宽+中缝"定位到屏外）
 * 另 hook `MainPanelController.updatePanelWidth()` after → 单面板时把宽度撑到
 * `屏宽 - 2*control_center_force_vertical_margin_end`（左右等边距, 满足"横屏撑满"诉求）。
 * 内屏横屏 2340px ≥ 2188px → 条件不成立 → **双面板横屏原样保留**（不动内屏）。
 * 竖屏一律不动; 转回竖屏自动恢复（每次 updateResources 都重算）。
 *
 * 注入: 路径 A(§43.6.3b)——插件类在宿主 classloader 的【子级】独立 PathClassLoader,
 *   hook PluginFactory.createPluginContext() after 拿 ContextWrapper.classLoader;
 *   插件运行在 com.android.systemui 进程(manifest 无独立进程)。
 * 开关: persist.flipunlock.ui.qspanelwidth（默认 true）
 */
object QSPanelWidthFixHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "android")

    /** 控制中心面板控制器候选类名: 设备 dex 明文 + jadx 反混淆产物防御。 */
    private val CONTROLLER_CANDIDATES = listOf(
        "miui.systemui.controlcenter.panel.main.MainPanelController",
        "miui.systemui.controlcenter.panel.main.p113qs.MainPanelController",
    )

    /** 插件工具类（getInVerticalMode 总闸）。 */
    private val COMMON_UTILS_CANDIDATES = listOf(
        "miui.systemui.util.CommonUtils",
    )

    private const val STYLE_CLASS =
        "miui.systemui.controlcenter.panel.main.MainPanelController\$Style"

    /** 插件 dimen 缓存（配置不变时恒定）。 */
    @Volatile private var dimenPanelWidth = -1
    @Volatile private var dimenCenterMargin = -1
    @Volatile private var dimenEndMargin = -1

    /** 竖屏日志只打一次, 避免 updateResources 反复刷屏。 */
    @Volatile private var portraitLogged = false

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
                // SystemUI 进程会为多个插件(AOD/全局操作/控制中心…, 不同 APK)调 createPluginContext;
                // 必须**命中控制中心类才锁定**, 否则首个插件就把 hook 位占死(初版静默失效根因)。
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
        val styleCls = runCatching { pluginLoader.loadClass(STYLE_CLASS) }.getOrNull() ?: return false
        val styleFields = styleCls.enumConstants?.joinToString(",") { it.toString() } ?: "?"

        // ── ① 总闸: getInVerticalMode → 双面板放不下时按竖屏处理 ──
        var verticalHooked = false
        for (name in COMMON_UTILS_CANDIDATES) {
            val cls = runCatching { pluginLoader.loadClass(name) }.getOrNull() ?: continue
            val m = runCatching { cls.method("getInVerticalMode", Context::class.java) }.getOrNull()
                ?: run {
                    log("QSPanelWidthFix: $name 无 getInVerticalMode(Context), skip")
                    continue
                }
            hook(m, Hooker { chain ->
                val ctx = chain.args.getOrNull(0) as? Context
                if (ctx != null && !fitsTwoPanels(ctx)) true else chain.proceed()
            })
            verticalHooked = true
            log("QSPanelWidthFix: ✓ $name.getInVerticalMode → 双面板放不下时 true(退回单面板)")
        }
        if (!verticalHooked) log("QSPanelWidthFix: CommonUtils.getInVerticalMode 未命中, 仅做宽度兜底")

        // ── ② 宽度: 单面板时撑满屏宽（左右等边距）──
        for (candidate in CONTROLLER_CANDIDATES) {
            val cls = runCatching { pluginLoader.loadClass(candidate) }.getOrNull() ?: continue
            val updatePanelWidth = runCatching { cls.method("updatePanelWidth") }.getOrNull()
                ?: run {
                    log("QSPanelWidthFix: $candidate 无 updatePanelWidth(), skip")
                    continue
                }
            hook(updatePanelWidth, after { chain, result ->
                val controller = chain.thisObject ?: return@after result
                val ctx = runCatching { controller.callMethod("getContext") as? Context }.getOrNull()
                if (ctx == null) {
                    log("QSPanelWidthFix: getContext() 取不到, skip")
                    return@after result
                }
                val style = runCatching { controller.callMethod("getStyle") }.getOrNull()
                val dm = ctx.resources.displayMetrics
                val old = runCatching { controller.getField("panelWidth") as? Int }.getOrNull() ?: -1
                if (fitsTwoPanels(ctx)) {
                    // 竖屏, 或横屏但双面板放得下(内屏) → 保持插件原值
                    if (dm.widthPixels <= dm.heightPixels && !portraitLogged) {
                        portraitLogged = true
                        log("QSPanelWidthFix: 竖屏不动 panelWidth=$old style=$style 屏 ${dm.widthPixels}x${dm.heightPixels}")
                    }
                    return@after result
                }
                val end = pluginDimen(ctx, "control_center_force_vertical_margin_end", 2)
                val newWidth = dm.widthPixels - end * 2
                if (newWidth > 0 && newWidth != old) {
                    runCatching { controller.setField("panelWidth", newWidth) }
                        .onSuccess {
                            log("QSPanelWidthFix: 横屏双面板放不下 → 单面板宽 $old → $newWidth px " +
                                "(屏 ${dm.widthPixels}x${dm.heightPixels} 边距=$end style=$style) — 撑满")
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

    /**
     * 双面板(2×panelWidth+中缝)在**当前屏宽**下是否放得下。
     * 放得下(竖屏 / 内屏横屏) → 保持插件原生行为; 放不下(外屏横屏) → 退回单面板。
     */
    private fun fitsTwoPanels(ctx: Context): Boolean {
        val dm = ctx.resources.displayMetrics
        val rotation = runCatching { ctx.display?.rotation }.getOrNull()
        val landscape = dm.widthPixels > dm.heightPixels ||
            rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        if (!landscape) return true
        val panel = pluginDimen(ctx, "control_center_universal_4_rows_with_margin_size", 0)
        val center = pluginDimen(ctx, "control_center_horizontal_margin_center", 1)
        if (panel <= 0) return true // dimen 取不到 → 不冒险
        return panel * 2 + center <= dm.widthPixels
    }

    /** 取插件资源 dimen(按资源名, 不依赖 R 类混淆名); 取不到返回 -1。 */
    private fun pluginDimen(ctx: Context, name: String, slot: Int): Int {
        val cache = when (slot) {
            0 -> dimenPanelWidth
            1 -> dimenCenterMargin
            else -> dimenEndMargin
        }
        if (cache >= 0) return cache
        val res = ctx.resources
        val id = runCatching { res.getIdentifier(name, "dimen", ctx.packageName) }.getOrNull() ?: 0
        val value = if (id != 0) runCatching { res.getDimensionPixelSize(id) }.getOrDefault(-1) else -1
        when (slot) {
            0 -> dimenPanelWidth = value
            1 -> dimenCenterMargin = value
            else -> dimenEndMargin = value
        }
        return value
    }
}
