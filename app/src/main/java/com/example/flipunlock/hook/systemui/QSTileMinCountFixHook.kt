package com.example.flipunlock.hook.systemui

import android.content.res.Resources
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 解除控制中心编辑模式"最少磁贴数"限制（2026-08-19 v6: 新增保险6 插件路径核心, 内屏样式版生效）。
 *
 * 现象（用户实测）: 控制中心点"编辑"→ 进入磁贴增删模式, 可编辑磁贴删到 8 个后磁贴右上角
 *   减号标签消失（删不动）。目标: 可编辑磁贴能删光, 只保留 4 个固定磁贴。
 *
 * 编辑路径有两条（2026-08-19 flip2-systemuiplugin + b5c1 systemui-plugin 反编译实锤, refMD §43.6.3a）:
 *   ① AOSP/Compose 路径（systemui 主 APK）: EditModeButtonKt→EditModeViewModel._isEditing=true
 *     → tiles 流 lambda(EditModeViewModel$tiles$...) 读 minNumberOfTiles(MinimumTilesResourceRepository=12)
 *     → currentTiles.size()<=12 → availableEditActions 无 REMOVE → EditTileKt 减号消失
 *   ② **插件路径（内屏样式版 VERTICAL 控制中心实际走的路径, systemui-plugin 插件）**:
 *     MainPanelStyleController.updateStyle: flipDevice && isTinyScreen → COMPACT;
 *     属性1 下 flipDevice=false → VERTICAL(内屏样式) → 编辑按钮 EditButtonController.available()=true
 *     → 点击 EditButtonController$onBindViewHolder$1$1 → QSListController.startQuery(Mode.EDIT)
 *     → TileQueryHelper 查询 → distributeTileInfo → changeMode(EDIT) → getListItems() 返回编辑列表
 *     → 减号显示: QSTileItemView.getShowMark()=(removable||!added) && mode!=NORMAL
 *     → 减号点击: QSRecord$markClickAction$1: added&&removable→removeTile; added&&!removable→无操作
 *     → **删除判定(三处硬编码 8, QSListController 内)**: distributeTiles/addTile/removeTile
 *       里 addedTiles.size() <= 8 → QSRecord.setRemovable(false) → 减号隐藏+点击无效
 *
 * 修复（v6 六层全防, flip1/2 通用; 内屏样式版核心=保险6, 其余 AOSP/Compose 兜底）:
 *   保险1: Resources.getInteger(int) → quick_settings_min_num_tiles → MIN_TILES(4)（资源层源头）
 *   保险3: MinimumTilesResourceRepository.<init> after → 反射 minNumberOfTiles = MIN_TILES
 *   保险4: EditTileViewModel.<init> after → availableEditActions 反射 add(REMOVE)
 *   保险5: hook EditModeViewModel$tiles 生成 lambda 的 emit/before → 结果 availableEditActions 全部 +REMOVE
 *   保险6(核心, 插件路径): hook QSRecord.setRemovable(boolean) before → 参数强制 true（恒可删,
 *          通杀 QSListController 三处判定）。setRemovable 调用点仅 QSListController 三处(全库 grep
 *          确认), 只影响 addedTiles(可编辑磁贴), 固定磁贴/候选磁贴不受影响; 构造默认 removable=true
 *          语义一致, 方法内 if(removable==z2) return 无副作用。
 * 开关: persist.flipunlock.ui.qstilemin（默认 true）
 */
object QSTileMinCountFixHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "android")

    /** 目标最少磁贴数 = 4 个固定磁贴（亮度/音量、wifi、数据、播放）。 */
    private const val MIN_TILES = 4

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

        // ── 保险 6(核心, 插件路径): QSRecord.setRemovable(boolean) before → 参数强制 true ──
        // 内屏样式版(VERTICAL)控制中心编辑页由 systemui-plugin 插件 View 体系接管(非 Compose):
        //   QSListController 三处(distributeTiles:707/addTile:868/removeTile:1161)
        //   `addedTiles.size() <= 8 → QSRecord.setRemovable(false)` → QSTileItemView 减号隐藏
        //   + QSRecord$markClickAction$1 点击无效(added && !removable → 无操作)。
        // 强制 true = 减号恒显示 + 点击恒可删, 通杀三处判定, 无需逐一 hook。
        // setRemovable 调用点仅 QSListController 三处(全库 grep 确认无其他调用) → 只影响
        // addedTiles(可编辑磁贴); 固定磁贴不在 addedTiles、候选磁贴不走 setRemovable, 均不受影响。
        safeHook("QSTileMinCountFix.6") {
            val qsRecordCandidates = listOf(
                // 设备 dex 明文类名(jadx "renamed from" 注释实锤, refMD §43.6.3a)
                "miui.systemui.controlcenter.panel.main.qs.QSRecord",
                // jadx 反混淆产物(p113qs), 防御不同固件/反编译命名差异
                "miui.systemui.controlcenter.panel.main.p113qs.QSRecord",
            )
            for (candidate in qsRecordCandidates) {
                val cls = runCatching { cl.findClassUp(candidate) }.getOrNull() ?: continue
                val setRemovable = runCatching {
                    cls.method("setRemovable", Boolean::class.javaPrimitiveType!!)
                }.getOrNull()
                if (setRemovable == null) {
                    log("QSTileMinCountFix: 保险6 $candidate 无 setRemovable(boolean), skip")
                    continue
                }
                hook(setRemovable, before { chain ->
                    chain.args[0] = true
                })
                log("QSTileMinCountFix: 保险6 ✓ ${cls.name}.setRemovable → 恒 true(减号恒显示)")
            }
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
        //    判定链(flip2 dex 反汇编实锤, §43.6.3): EditModeViewModel$tiles$lambda$10$$inlined$map$1$2.emit
        //      → size<=minNumberOfTiles → availableEditActions 无 REMOVE → 减号消失。
        //    本保险直接在判定结果处注入 REMOVE(绕过 minNumberOfTiles 整个判定)。
        //    ⚠️ 2026-08-19 §43.6.3a: 内屏样式版编辑页走插件(保险6), 本保险仅 AOSP/Compose 入口兜底。
        safeHook("QSTileMinCountFix.5") {
            val lambdaCandidates = listOf(
                "com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel\$tiles\$lambda\$10\$\$inlined\$map\$1\$2",
                "com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel\$tiles\$1\$2",
            )
            val actionsClsName = "com.android.systemui.qs.panels.ui.viewmodel.AvailableEditActions"
            for (candidate in lambdaCandidates) {
                val cls = runCatching { cl.loadClass(candidate) }.getOrNull() ?: continue
                // emit(value: Object, continuation: Continuation) — FlowCollector 方法
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
