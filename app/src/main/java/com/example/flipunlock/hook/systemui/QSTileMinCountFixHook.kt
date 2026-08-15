package com.example.flipunlock.hook.systemui

import android.content.res.Resources
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 解除控制中心编辑模式"最少磁贴数"限制（2026-08-15 v4: 12→4, 保留 4 个固定磁贴）。
 *
 * 现象（用户实测）: 控制中心点"编辑"→ 进入磁贴增删模式, 最少磁贴数 12
 *   （quick_settings_min_num_tiles=12 = 4 固定 + 8 可编辑）, 删到 12 个后磁贴右上角
 *   减号标签消失（删不动）。目标: 可编辑磁贴能删光, 只保留 4 个固定磁贴 → 12 改 4。
 *
 * 逻辑链（2026-08-15 flip2 设备 dex 反汇编实锤 + b5c1-systemui 反编译）:
 *   编辑入口: Compose 控制中心"编辑"按钮(EditModeButtonKt)→ EditModeViewModel._isEditing=true
 *   → EditModeViewModel.tiles 流 → EditModeViewModel$tiles$lambda$10$$inlined$map$1$2.emit()
 *     读取链(字节级实锤, 4404224-4404256):
 *       iget-object  this$0.minTilesInteractor            (EditModeViewModel)
 *       iget-object  v.minimumTilesRepository             (MinimumTilesInteractor)
 *       iget        v.minNumberOfTiles                    (MinimumTilesResourceRepository = 12)
 *       iget-object  $editTilesData$inlined.stockTiles     (当前磁贴列表)
 *     判定: 当前磁贴数 <= minNumberOfTiles(12) → availableEditActions 不含 REMOVE
 *   → EditTileViewModel.availableEditActions(SetBuilder)
 *   → EditTileKt(编辑页磁贴 UI): 右上角减号标签 = availableEditActions.contains(REMOVE)
 *   → 数量 <= 12 → 无 REMOVE → 减号消失（用户实测）✓
 *
 * 修复（v4 四层全防, flip1/2 通用; 目标值 MIN = 4 = 固定磁贴数）:
 *   保险1: Resources.getInteger(int) → quick_settings_min_num_tiles → 4（资源层源头, 所有读取点通杀）
 *   保险3: MinimumTilesResourceRepository.<init> after → 反射 minNumberOfTiles = 4
 *          （字段层源头; 设备真实类名无 R8 混淆包名: com.android.systemui.qs.pipeline...）
 *   保险4: EditTileViewModel.<init> after → availableEditActions 反射 add(REMOVE)
 *          （判定层兜底: 源头改不动时直接让减号恒显示）
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
