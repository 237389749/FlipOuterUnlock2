package com.example.flipunlock.hook.systemui

import android.content.res.Resources
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 解除控制中心编辑模式"最少磁贴数"限制（2026-08-14 重写 v3, 修复上一版不生效的两个根因）。
 *
 * 现象（用户实测）: 控制中心点"编辑"→ 进入磁贴增删模式, 最少磁贴数 12
 *   （quick_settings_min_num_tiles=12 = 4 固定 + 8 可编辑）, 删到下限后"移除"消失（删不动）。
 *
 * 逻辑链（b5c1-systemui 反编译, refMD §43.6/43.6.1）:
 *   编辑入口: EditTile("编辑"磁贴)→ handleClick→msg 1001→MiuiQSPanel→MiuiQSCustomizerController.show()
 *     老 View 编辑页(标题"编辑"): MiuiTileAdapter = com.android.systemui.p037qs.customize.MiuiTileAdapter
 *       （R8 混淆包名 p037, b5c1/flip2/common-systemui 三处一致）:
 *       构造(295): mMinNumTiles = getResources().getInteger(quick_settings_min_num_tiles) = 12
 *       长按(386): 若 pos<mEditIndex && mCurrentSpecs.size()>mMinNumTiles 才弹"移动/移除"菜单 → 删不动
 *       拖放(165 canDropOver): size<=mMinNumTiles 时禁止拖出编辑区 → 删不动
 *   Compose 编辑页(EditModeButtonKt→EditModeKt, QSFragmentCompose): 删除=removeTiles 无条件;
 *     MinimumTilesResourceRepository.minNumberOfTiles 同读该资源(Compose pipeline, 兜底)。
 *
 * 上一版(e52e47e)不生效的两个根因（本版已修）:
 *   ① 保险2 类名写成混淆前的 com.android.systemui.qs.customize.MiuiTileAdapter, 真实类是
 *      com.android.systemui.p037qs.customize.MiuiTileAdapter → findClassUp 返回 null → 保险2 从未生效
 *   ② systemui 进程以 pkg=android 回调时 param.classLoader 是框架加载器, 不含 APK 类(§43 已知)
 *      → 用 processClassLoader() 取进程 Application classLoader 替代
 *
 * 修复（三层全防, flip1/2 通用）:
 *   保险1: Resources.getInteger(int) → quick_settings_min_num_tiles → 0（所有读取点通杀）
 *   保险2: 候选类名枚举（MiuiTileAdapter/TileAdapter, p037+无混淆各一）hook 构造 after
 *          反射置 mMinNumTiles=0（field 是 public final, setAccessible 后 setInt）
 *   保险3: MinimumTilesResourceRepository 构造 after 反射置 minNumberOfTiles=0（Compose 兜底）
 * 开关: persist.flipunlock.ui.qstilemin（默认 true）
 */
object QSTileMinCountFixHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "android")

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

        // ── 保险 1: Resources.getInteger → 0 ──
        safeHook("QSTileMinCountFix.1") {
            runCatching {
                val resClass = cl.loadClass("android.content.res.Resources")
                val method = resClass.method("getInteger", Int::class.javaPrimitiveType!!)
                hook(method) { chain ->
                    val res = chain.thisObject as? Resources ?: return@hook chain.proceed()
                    val id = chain.args[0] as? Int ?: return@hook chain.proceed()
                    val name = runCatching { res.getResourceName(id) }.getOrNull()
                    if (name != null && name.endsWith("quick_settings_min_num_tiles")) {
                        log("QSTileMinCountFix: 保险1 $name → 0")
                        return@hook 0
                    }
                    chain.proceed()
                }
                log("QSTileMinCountFix: ✓ 保险1 Resources.getInteger hooked")
            }.onFailure { log("QSTileMinCountFix: 保险1 failed: ${it.message}") }
        }

        // ── 保险 2: 编辑页 Adapter 构造后 mMinNumTiles=0 ──
        safeHook("QSTileMinCountFix.2") {
            // R8 混淆包名 p037 + 无混淆两候选, 防版本漂移
            val adapterCandidates = listOf(
                "com.android.systemui.p037qs.customize.MiuiTileAdapter",
                "com.android.systemui.qs.customize.MiuiTileAdapter",
                "com.android.systemui.p037qs.customize.TileAdapter",
                "com.android.systemui.qs.customize.TileAdapter",
            )
            for (candidate in adapterCandidates) {
                val cls = runCatching { cl.loadClass(candidate) }.getOrNull() ?: continue
                val field = runCatching { cls.field("mMinNumTiles") }.getOrNull()
                    ?: run {
                        log("QSTileMinCountFix: 保险2 $candidate 无字段 mMinNumTiles, skip")
                        continue
                    }
                val ctor = runCatching { cls.declaredConstructors.firstOrNull() }.getOrNull()
                if (ctor == null) {
                    log("QSTileMinCountFix: 保险2 $candidate 无构造, skip")
                    continue
                }
                hook(ctor, after { chain, _ ->
                    runCatching { field.setInt(chain.thisObject, 0) }
                        .onFailure { log("QSTileMinCountFix: 保险2 $candidate setInt failed: ${it.message}") }
                })
                log("QSTileMinCountFix: ✓ 保险2 ${cls.name}.<init> → mMinNumTiles=0")
            }
        }

        // ── 保险 3: MinimumTilesResourceRepository(Compose pipeline)构造后 minNumberOfTiles=0 ──
        safeHook("QSTileMinCountFix.3") {
            val repoCandidates = listOf(
                "com.android.systemui.p037qs.pipeline.data.repository.MinimumTilesResourceRepository",
                "com.android.systemui.qs.pipeline.data.repository.MinimumTilesResourceRepository",
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
                    runCatching { field.setInt(chain.thisObject, 0) }
                        .onFailure { log("QSTileMinCountFix: 保险3 $candidate setInt failed: ${it.message}") }
                })
                log("QSTileMinCountFix: ✓ 保险3 ${cls.name}.<init> → minNumberOfTiles=0")
            }
        }
    }
}
