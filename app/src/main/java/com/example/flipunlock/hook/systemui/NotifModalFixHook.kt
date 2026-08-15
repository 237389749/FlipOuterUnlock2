package com.example.flipunlock.hook.systemui

import android.view.View
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 折叠态(外屏)点通知不弹模态（2026-08-15, 方案 A）。
 *
 * 背景（flip2 实测 + 反编译实锤）:
 *   折叠态(外屏)点通知 → ExpandableNotificationRow:1489 调
 *   ModalControllerImpl.tryAnimEnterModal(row) → 弹通知模态
 *   (ModalWindowView.enterModal; 模态菜单含 mFlipTipItem "展开到内屏继续操作")
 *   → 模态显示引导"展开到内屏继续操作", 不直接打开 app。
 *   tryAnimEnterModal 内部判断: statusBarStateController.isExpanded() 且非特殊态
 *   且 !MiuiConfigs.isTinyScreen(context) 时 return —— 即外屏(tiny)时继续弹模态。
 *
 * 方案 A: hook ModalControllerImpl.tryAnimEnterModal(ExpandableNotificationRow)
 *   → MiuiConfigs.isTinyScreen(context)(外屏 1208×1392@520dp≈428dp ≤670)时
 *   no-op(不弹模态) → ModalControllerImpl.isModal 保持 false →
 *   NotificationClicker.onClick 的 `isTinyScreen && isModal → return` 不命中 →
 *   继续走 StatusBarNotificationActivityStarter 直接启动 app(属性 4 行为)。
 *   内屏(非 tiny)正常弹模态, 不影响通知聚焦/长按菜单。
 *
 * 进程: com.android.systemui。类/方法设备 dex 明文确认。
 */
object NotifModalFixHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "android")

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.enabled) return
        val process = currentProcessName()
        if (process != "com.android.systemui") {
            log("NotifModalFix: skip, process=$process")
            return
        }
        log("NotifModalFix: loading for ${param.packageName} (process=$process)")
        val cl = processClassLoader(param.classLoader)
        safeHook("NotifModalFix") {
            runCatching {
                val cls = cl.loadClass(
                    "com.android.systemui.statusbar.notification.modal.ModalControllerImpl")
                val method = cls.method("tryAnimEnterModal", View::class.java)
                hook(method) { chain ->
                    val row = chain.args[0] as? View ?: return@hook chain.proceed()
                    val tiny = runCatching {
                        val mc = cl.loadClass("com.miui.utils.configs.MiuiConfigs")
                        mc.method("isTinyScreen", android.content.Context::class.java)
                            .invoke(null, row.context) as? Boolean
                    }.getOrNull() ?: false
                    if (tiny) {
                        log("NotifModalFix: ✓ 折叠态(tiny)点通知不弹模态 → 直接走启动")
                        return@hook null
                    }
                    chain.proceed()
                }
                log("NotifModalFix: ✓ ModalControllerImpl.tryAnimEnterModal hooked")
            }.onFailure { log("NotifModalFix failed: ${it.message}") }
        }
    }
}
