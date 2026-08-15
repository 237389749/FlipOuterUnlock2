package com.example.flipunlock.hook.systemui

import android.content.ContentResolver
import android.provider.Settings
import android.text.TextUtils
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * flip 磁贴数量限制解除（2026-08-15, 基于 systemuiplugin/flipQs 重写）。
 *
 * 背景: flip2 控制中心磁贴编辑有两套入口——
 *   ① systemui 主 APK Compose 编辑页(EditModeViewModel, 12 下限, 减号标签)——QSTileMinCountFixHook
 *   ② **MIUISystemUIPlugin(系统界面组件)的 flipQs 设置页**(QSFlipQuickSettingActivity, **6 下限**)
 *
 * 本 hook 针对 ②(flip 专用小屏磁贴设置, 独立进程 miui.systemui.plugin)。
 *
 * 限制链(插件反编译, flip2-systemuiplugin 导出 + 设备 APK 实锤):
 *   QSFlipQuickSettingActivity.onStop() → syncData()
 *     → 若磁贴有变化 && arrayList.size() >= 6 → Settings.Secure.putStringForUser(
 *         "sysui_flip_qs_tiles", join(",", addedSpecs))
 *     → **数量 < 6 不保存 → 删到 <6 重启还原(最少 6 个磁贴)**
 *   (增删动作 removeAndAddItem 是纯移动, 无数量限制; 限制只在保存闸门)
 *
 * 修复: hook syncData() after → 原逻辑 <6 不保存时, 反射取 mAdapter.getAddedItems()
 *   的 spec 列表, 补一次 putStringForUser(无条件保存)。
 *
 * 进程: miui.systemui.plugin(插件独立进程, 无 process 声明的 Activity 默认进程)
 * 类名设备实锤明文: miui.systemui.controlcenter.flipQs.QSFlipQuickSettingActivity
 * 开关: persist.flipunlock.ui.qstilemin(与 QSTileMinCountFixHook 共用)
 * ⚠️ LSPosed scope 需勾选 miui.systemui.plugin
 */
object FlipQsFixHook : BaseHook() {

    override val targetPackages = listOf("miui.systemui.plugin")

    /** flip 磁贴保存键(插件 QSFlipUtils.SYSTEM_UI_FLIP_QS_TILES)。 */
    private const val FLIP_QS_TILES = "sysui_flip_qs_tiles"

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.qsTileMinCount) {
            log("FlipQsFix: skip, toggle off")
            return
        }
        val process = currentProcessName()
        if (process != "miui.systemui.plugin") {
            log("FlipQsFix: skip, process=$process")
            return
        }
        log("FlipQsFix: loading for ${param.packageName} (process=$process)")
        val cl = processClassLoader(param.classLoader)
        safeHook("FlipQsFix") {
            runCatching {
                val cls = cl.loadClass(
                    "miui.systemui.controlcenter.flipQs.QSFlipQuickSettingActivity")
                val syncData = cls.method("syncData")
                hook(syncData, after { chain, _ ->
                    runCatching {
                        val activity = chain.thisObject ?: return@after chain.proceed()
                        val adapter = activity.getField("mAdapter")
                        val addedItems = adapter.callMethod("getAddedItems") as? List<*>
                            ?: return@after chain.proceed()
                        val specs = addedItems.mapNotNull { item ->
                            item?.callMethod("getSpec") as? String
                        }
                        // 原逻辑 size>=6 已保存; 只有 <6(被吞)才补
                        if (specs.size >= 6) return@after chain.proceed()
                        val joined = TextUtils.join(",", specs)
                        val resolver = activity.callMethod("getContentResolver") as? ContentResolver
                            ?: return@after chain.proceed()
                        Settings.Secure.putStringForUser(
                            resolver, FLIP_QS_TILES, joined, null, false, -2, true)
                        log("FlipQsFix: ✓ 补保存 <6 flip 磁贴(${specs.size}): $joined")
                    }.onFailure { log("FlipQsFix: 补保存失败: ${it.message}") }
                })
                log("FlipQsFix: ✓ QSFlipQuickSettingActivity.syncData hooked")
            }.onFailure { log("FlipQsFix failed: ${it.message}") }
        }
    }
}
