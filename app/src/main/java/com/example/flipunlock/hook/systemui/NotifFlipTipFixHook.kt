package com.example.flipunlock.hook.systemui

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 移除通知模态菜单里的"展开到内屏继续操作"项（2026-08-15, 方案 B）。
 *
 * 背景（flip2 实测 + 反编译实锤, refMD §43.6.4 延伸）:
 *   折叠态(外屏)点通知 → NotificationClicker.onClick
 *   → ModalControllerImpl.enterModal(entry, showMenu, modalRow) → 通知模态窗口
 *     (NotificationModalWindowManager, 背景模糊其他通知隐藏 = 用户实测形态)
 *   → MiuiNotificationMenuRow.createMenuViews 构建菜单
 *   → 条件成立时创建 mFlipTipItem(MiuiNotificationMenuItem i2==-1 无图标分支,
 *     标题 = miui_notification_menu_title_no_drawable "展开到内屏继续操作",
 *     flip 字号 miui_notification_modal_menu_flip_text_size)
 *   → 模态底部显示"展开到内屏继续操作"
 *
 * 关键: 提示展示方是 systemui 通知模态(不走 InterceptActivityController/FlipTipView)
 *   → AppRestriction v2(StartActivityTipView/FlipTipView no-op)对这条链无效。
 *   属性 1 杀 flip 外屏可用逻辑 → 折叠态点通知弹此引导(属性 4 不弹, 用户实测)。
 *
 * 方案 B: hook MiuiNotificationMenuRow.createMenuViews(boolean) after
 *   → 遍历 mMenuItems 移除 flip 提示项(识别: === mFlipTipItem 字段 或
 *     contentDescription == "展开到内屏继续操作")。只消提示项, 不干扰正常菜单/启动。
 *
 * 进程: com.android.systemui。
 */
object NotifFlipTipFixHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "android")

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.enabled) return
        val process = currentProcessName()
        if (process != "com.android.systemui") {
            log("NotifFlipTipFix: skip, process=$process")
            return
        }
        log("NotifFlipTipFix: loading for ${param.packageName} (process=$process)")
        val cl = processClassLoader(param.classLoader)
        safeHook("NotifFlipTipFix") {
            runCatching {
                val cls = cl.loadClass(
                    "com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow")
                val method = cls.method("createMenuViews", Boolean::class.javaPrimitiveType!!)
                hook(method, after { chain, result ->
                    val row = chain.thisObject ?: return@after result
                    runCatching {
                        val items = row.getField("mMenuItems") as? MutableList<*> ?: return@after result
                        val flipTip = runCatching { row.getField("mFlipTipItem") }.getOrNull()
                        val it = items.iterator()
                        var removed = 0
                        while (it.hasNext()) {
                            val item = it.next()
                            val isFlip = (flipTip != null && item === flipTip) ||
                                runCatching {
                                    val desc = item?.let { it.callMethod("getContentDescription") } as? String
                                    desc == "展开到内屏继续操作"
                                }.getOrDefault(false)
                            if (isFlip) {
                                it.remove()
                                removed++
                            }
                        }
                        if (removed > 0) {
                            log("NotifFlipTipFix: ✓ 移除 flip 提示项 x$removed (菜单剩 ${items.size} 项)")
                        }
                    }.onFailure { log("NotifFlipTipFix: 移除失败: ${it.message}") }
                    result
                })
                log("NotifFlipTipFix: ✓ MiuiNotificationMenuRow.createMenuViews hooked")
            }.onFailure { log("NotifFlipTipFix failed: ${it.message}") }
        }
    }
}
