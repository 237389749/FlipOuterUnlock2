package com.example.flipunlock.hook.systemui

import android.content.ContextWrapper
import android.content.res.Resources
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 解除控制中心编辑模式"最少磁贴数"限制（2026-08-21 v7: 保险6 改走插件 PathClassLoader 路径 A）。
 *
 * 现象（用户实测）: 控制中心点"编辑"→ 可编辑磁贴删到 8 个后右上角减号消失（删不动）。
 * 目标: 可编辑磁贴能删光, 只保留 4 个固定磁贴。
 *
 * 编辑路径（refMD §43.6.3a/43.6.3b 实锤）:
 *   ① AOSP/Compose 路径（systemui 主 APK）: EditModeViewModel→minNumberOfTiles(12)→REMOVE 判定
 *   ② **插件路径（内屏样式版 VERTICAL 控制中心实际走的路径, systemui-plugin 插件类）**:
 *     MainPanelStyleController.updateStyle: flipDevice && isTinyScreen → COMPACT;
 *     属性1 下 flipDevice=false → VERTICAL(内屏样式) → 编辑按钮 EditButtonController.available()=true
 *     → 点击 → QSListController.startQuery(Mode.EDIT) → TileQueryHelper 查询(读 sysui_qs_tiles)
 *     → distributeTileInfo → changeMode(EDIT) → getListItems() 返回编辑列表
 *     → 减号显示: QSTileItemView.getShowMark()=(removable||!added)&&mode!=NORMAL
 *     → 减号点击: QSRecord$markClickAction$1: added&&removable→removeTile; added&&!removable→无操作
 *     → **删除判定: QSListController 内 MIN_TILE_COUNT=8(:74), 三处 addedTiles.size()<=8
 *       → QSRecord.setRemovable(false)（:707 distributeTiles / :868 addTile / :1161 removeTile）**
 *     → 保存闸门: TileQueryHelper.saveSpecs 无数量校验 → host.changeTiles → 主进程写 sysui_qs_tiles
 *       （删不动 100% 由 setRemovable(false) 决定, 保存无阻碍）
 *
 * v6 失效根因（§43.6.3b, 3 agent 实锤）: 插件类运行在 com.android.systemui 进程, 但由宿主插件
 *   框架用**独立 PathClassLoader** 加载(宿主 classloader 的子级, PluginInstance$PluginFactory
 *   createPluginContext 创建)。v6 用 processClassLoader+findClassUp(**沿 parent 链向上**)找类 →
 *   方向反了恒找不到 → `?: continue` 静默 skip(无日志) → 保险6 从未执行。
 *
 * 修复（v7 六层全防, flip1/2 通用; 内屏样式版核心=保险6 路径 A, 其余 AOSP/Compose 兜底）:
 *   保险1: Resources.getInteger(int) → quick_settings_min_num_tiles → MIN_TILES(4)（资源层源头）
 *   保险3: MinimumTilesResourceRepository.<init> after → 反射 minNumberOfTiles = MIN_TILES
 *   保险4: EditTileViewModel.<init> after → availableEditActions 反射 add(REMOVE)
 *   保险5: hook EditModeViewModel$tiles 生成 lambda 的 emit/before → 结果 availableEditActions 全部 +REMOVE
 *   保险6(核心, 插件路径, 路径 A): hook PluginFactory.createPluginContext() after → 返回
 *          ContextWrapper.classLoader(=插件 PathClassLoader) → loadClass QSRecord(明文+p113qs 双候选)
 *          → hook setRemovable(boolean) before → 参数强制 true（恒可删, 通杀三处判定; MixFlipMod
 *          ControlCenterHook 已验证的插件 hook 路径; isHooked 防 PluginFactory 多次调用重复 hook）。
 * 开关: persist.flipunlock.ui.qstilemin（默认 true）
 */
object QSTileMinCountFixHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "android")

    /** 目标最少磁贴数 = 4 个固定磁贴（亮度/音量、wifi、数据、播放）。 */
    private const val MIN_TILES = 4

    /** 插件 QSRecord 候选类名: 设备 dex 明文(renamed from 实锤 + MixFlipMod 明文验证) + jadx 反混淆产物防御。 */
    private val QS_RECORD_CANDIDATES = listOf(
        "miui.systemui.controlcenter.panel.main.qs.QSRecord",
        "miui.systemui.controlcenter.panel.main.p113qs.QSRecord",
    )

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.qsTileMinCount) {
            log("QSTileMinCountFix: skip, toggle off")
            return
        }
        val process = currentProcessName()
        if (process != "com.android.systemui") {
            log("QSTileMinCountFix: skip, process=$process")
            return
        }
        log("QSTileMinCountFix: loading for ${param.packageName} (process=$process)")
        // systemui 以 pkg=android 回调时 param.classLoader 不含 APK 类 → 取进程 Application classLoader
        val cl = processClassLoader(param.classLoader)

        // ── 保险 6(核心, 插件路径, 路径 A): 插件 classloader → QSRecord.setRemovable before 强制 true ──
        // 插件类在宿主 classloader 的【子级】独立 PathClassLoader(PluginFactory.createPluginContext
        // 创建, 缓存于 PluginInstanceInjector.sClassLoaders), findClassUp(向上) 找不到(v6 静默失效)。
        // 正确路径: hook createPluginContext after → 返回 ContextWrapper.classLoader 即插件 classloader
        //   （MixFlipMod/SystemUIHook.hookControlCenter 已验证; 类名明文 qs 包与 renamed from 一致）。
        // 时机=插件懒加载那一刻(控制中心首次创建), isHooked 防 PluginFactory 多次调用重复 hook。
        safeHook("QSTileMinCountFix.6") {
            val factoryCls = runCatching {
                cl.findClassUp("com.android.systemui.shared.plugins.PluginInstance\$PluginFactory")
            }.getOrNull()
            if (factoryCls == null) {
                log("QSTileMinCountFix: 保险6 PluginFactory 找不到, skip")
                return@safeHook
            }
            val createPluginContext = runCatching {
                factoryCls.method("createPluginContext")
            }.getOrNull()
            if (createPluginContext == null) {
                log("QSTileMinCountFix: 保险6 createPluginContext() 找不到, skip")
                return@safeHook
            }
            var hooked = false
            hook(createPluginContext, after { chain, result ->
                if (hooked) return@after result
                val wrapper = result as? ContextWrapper ?: return@after result
                val pluginLoader = wrapper.classLoader ?: return@after result
                for (candidate in QS_RECORD_CANDIDATES) {
                    val cls = runCatching { pluginLoader.loadClass(candidate) }.getOrNull() ?: continue
                    val setRemovable = runCatching {
                        cls.method("setRemovable", Boolean::class.javaPrimitiveType!!)
                    }.getOrNull()
                    if (setRemovable == null) {
                        log("QSTileMinCountFix: 保险6 $candidate 无 setRemovable(boolean), skip")
                        continue
                    }
                    hook(setRemovable, before { inner ->
                        inner.args[0] = true
                    })
                    hooked = true
                    log("QSTileMinCountFix: 保险6 ✓ ${cls.name}.setRemovable → 恒 true(减号恒显示)")
                }
                result
            })
            log("QSTileMinCountFix: 保险6 PluginFactory.createPluginContext hooked")
        }

        // ── 保险 1: Resources.getInteger → MIN_TILES(4) ──
        safeHook("QSTileMinCountFix.1") {
            runCatching {
                val resClass = cl.loadClass("android.content.res.Resources")
                val method = resClass.method("getInteger", Int::class.javaPrimitiveType!!)
                hook(method) { chain ->
                    val res = chain.thisObject as? Resources ?: return@hook chain.proceed()
                    val id = chain.args[0] as? Int ?: return@hook chain.proceed()
                    val name = runCatching { res.getResourceName(id) }.getOrNull()
                    if (name != null && name.endsWith("quick_settings_min_num_tiles")) {
                        log("QSTileMinCountFix: 保险1 $name -> $MIN_TILES")
                        return@hook MIN_TILES
                    }
                    chain.proceed()
                }
                log("QSTileMinCountFix: ✓ 保险1 Resources.getInteger hooked")
            }.onFailure { log("QSTileMinCountFix: 保险1 failed: ${it.message}") }
        }

        // ── 保险 3: MinimumTilesResourceRepository.<init> after → minNumberOfTiles = MIN_TILES ──
        safeHook("QSTileMinCountFix.3") {
            val repoCandidates = listOf(
                "com.android.systemui.qs.pipeline.data.repository.MinimumTilesResourceRepository",
                "com.android.systemui.p037qs.pipeline.data.repository.MinimumTilesResourceRepository",
            )
            for (candidate in repoCandidates) {
                val cls = runCatching { cl.loadClass(candidate) }.getOrNull() ?: continue
                val field = runCatching { cls.field("minNumberOfTiles") }.getOrNull()
                    ?: run {
                        log("QSTileMinCountFix: 保险3 $candidate 无字段 minNumberOfTiles, skip")
                        continue
                    }
                val ctor = runCatching { cls.declaredConstructors.firstOrNull() }.getOrNull() ?: continue
                hook(ctor, after { chain, _ ->
                    runCatching { field.setInt(chain.thisObject, MIN_TILES) }
                        .onSuccess { log("QSTileMinCountFix: 保险3 ✓ ${cls.name}.<init> → minNumberOfTiles=$MIN_TILES") }
                        .onFailure { log("QSTileMinCountFix: 保险3 $candidate setInt failed: ${it.message}") }
                })
                log("QSTileMinCountFix: 保险3 hooked ${cls.name}")
            }
        }

        // ── 保险 4: EditTileViewModel.<init> after → availableEditActions add(REMOVE) ──
        safeHook("QSTileMinCountFix.4") {
            val editTileCandidates = listOf(
                "com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel",
                "com.android.systemui.p037qs.panels.p041ui.viewmodel.EditTileViewModel",
            )
            val actionsClsName = "com.android.systemui.qs.panels.ui.viewmodel.AvailableEditActions"
            for (candidate in editTileCandidates) {
                val cls = runCatching { cl.loadClass(candidate) }.getOrNull() ?: continue
                val field = runCatching { cls.field("availableEditActions") }.getOrNull()
                    ?: run {
                        log("QSTileMinCountFix: 保险4 $candidate 无字段 availableEditActions, skip")
                        continue
                    }
                val ctor = runCatching { cls.declaredConstructors.firstOrNull() }.getOrNull() ?: continue
                hook(ctor, after { chain, _ ->
                    runCatching {
                        val actions = field.get(chain.thisObject) ?: return@after chain.proceed()
                        val add = actions.javaClass.method("add", Any::class.java)
                        val removeEnum = runCatching {
                            actions.javaClass.classLoader.loadClass(actionsClsName).field("REMOVE").get(null)
                        }.getOrNull() ?: return@after chain.proceed()
                        add.invoke(actions, removeEnum)
                        log("QSTileMinCountFix: 保险4 ✓ ${cls.name}.<init> → availableEditActions+REMOVE")
                    }.onFailure { log("QSTileMinCountFix: 保险4 $candidate failed: ${it.message}") }
                })
                log("QSTileMinCountFix: 保险4 hooked ${cls.name}")
            }
        }

        // ── 保险 5(直接数量判定): hook 编辑页 tiles 数据流生成 lambda 的 emit/before,
        //    结果 List<EditTileViewModel> 的 availableEditActions 全部加 REMOVE。
        //    ⚠️ 内屏样式版编辑页走插件(保险6), 本保险仅 AOSP/Compose 入口兜底(§43.6.3a)。
        safeHook("QSTileMinCountFix.5") {
            val lambdaCandidates = listOf(
                "com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel\$tiles\$lambda\$10\$\$inlined\$map\$1\$2",
                "com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel\$tiles\$1\$2",
            )
            val actionsClsName = "com.android.systemui.qs.panels.ui.viewmodel.AvailableEditActions"
            for (candidate in lambdaCandidates) {
                val cls = runCatching { cl.loadClass(candidate) }.getOrNull() ?: continue
                val emit = runCatching {
                    cls.method("emit", Any::class.java, kotlin.coroutines.Continuation::class.java)
                }.getOrNull()
                val target = emit
                if (target == null) {
                    log("QSTileMinCountFix: 保险5 $candidate 无 emit 方法, skip")
                    continue
                }
                hook(target, before { chain ->
                    val value = chain.args[0] as? List<*> ?: return@before
                    var added = 0
                    for (vm in value) {
                        val v = vm ?: continue
                        val actions = runCatching { v.getField("availableEditActions") }.getOrNull()
                            ?: continue
                        runCatching {
                            val add = actions.javaClass.method("add", Any::class.java)
                            val removeEnum = actions.javaClass.classLoader
                                .loadClass(actionsClsName).field("REMOVE").get(null)
                            add.invoke(actions, removeEnum)
                            added++
                        }.onFailure { /* 单元素失败忽略 */ }
                    }
                    if (added > 0) log("QSTileMinCountFix: 保险5 ✓ 数量判定结果 +REMOVE ($added 磁贴)")
                })
                log("QSTileMinCountFix: 保险5 hooked ${cls.name}.emit")
            }
        }
    }
}
