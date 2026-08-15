package com.example.flipunlock.hook.identity

import android.content.Context
import android.content.res.Configuration
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 属性层死角补充：getScreenType + isTinyScreen 系列 → 内屏/非 tiny（2026-08-15 重写）。
 *
 * 审查结论（旧 FlipOuterUnlock DeviceIdentityHook + ScreenTypeHook 移植分析）:
 *   属性层(属性1/boot前 resetprop)已覆盖所有读 persist.sys.multi_display_type 的路径:
 *     isFlipDevice / IS_FLIP / IS_FOLD / detectType / 静态字段 → 无需重复 hook。
 *   属性层死角(运行时状态, 不读属性)需要 hook 补充:
 *     ① Configuration.getScreenType()        —— MIUI 注入, "我在哪个屏"判断(折叠=1/外屏)
 *     ② MiuiConfigs.isTinyScreen(Context)    —— maxBounds/density 计算(≤670dp)
 *     ③ MiuiConfigs.isFlipTinyScreen(Context)—— isFlipDevice && isTinyScreen(半覆盖, 保险)
 *     ④ miuix.os.DeviceHelper.isTinyScreen   —— detectType==4 && screenType==1
 *     ⑤ miuix.device.DeviceUtils.isFlipTinyScreen
 *
 * 动机（用户实测）: flip2 通知中心点 TIM 弹"展开到内屏继续操作"(微信正常)。
 *   通知链(NotificationClicker.onClick / ModalController.tryAnimEnterModal)用
 *   MiuiConfigs.isTinyScreen 判断 → tiny=true 时走折叠态拦截/模态。
 *   isTinyScreen→false → 单击直接走 StatusBarNotificationActivityStarter 启动(微信行为)。
 *
 * 重写差异(相对旧项目):
 *   - 移除 isFlipDevice 系(属性层已覆盖, 不重复)
 *   - MiuiConfigs 双包名: flip2=com.miui.utils.configs(新), flip1=miui.util(旧)
 *   - 仅补死角, 影响面最小
 *
 * 进程: 全进程("*"), 旧项目同款(伪装手机需所有 app 一致)。
 * ⚠️ 副作用: 外屏 UI 按非 tiny 布局(状态栏/控制中心 flip tiny 布局失效)——旧项目同款, 实测评估。
 */
object TinyScreenFixHook : BaseHook() {

    override val targetPackages = listOf("*")

    override fun setupHooks(param: PackageReadyParam) {
        val cl = processClassLoader(param.classLoader)
        safeHook("TinyScreenFix") {
            // ① Configuration.getScreenType → 0 (SCREEN_TYPE_EXPAND 内屏)
            runCatching {
                val m = Configuration::class.java.method("getScreenType")
                hook(m, replaceResult(0))
                log("TinyScreenFix: ✓ Configuration.getScreenType → 0")
            }.onFailure { log("TinyScreenFix: getScreenType failed: ${it.message}") }

            // ②③ MiuiConfigs.isTinyScreen / isFlipTinyScreen → false(双包名)
            val configsNames = listOf(
                "com.miui.utils.configs.MiuiConfigs", // flip2/bixi
                "miui.util.MiuiConfigs",               // flip1/老
            )
            for (cn in configsNames) {
                val cls = runCatching { cl.loadClass(cn) }.getOrNull() ?: continue
                runCatching {
                    hook(cls.method("isTinyScreen", Context::class.java), replaceResult(false))
                    log("TinyScreenFix: ✓ $cn.isTinyScreen → false")
                }.onFailure { }
                runCatching {
                    hook(cls.method("isFlipTinyScreen", Context::class.java), replaceResult(false))
                    log("TinyScreenFix: ✓ $cn.isFlipTinyScreen → false")
                }.onFailure { }
            }

            // ④ miuix.os.DeviceHelper.isTinyScreen → false(存在则 hook)
            runCatching {
                val cls = cl.loadClass("miuix.os.DeviceHelper")
                hook(cls.method("isTinyScreen", Context::class.java), replaceResult(false))
                log("TinyScreenFix: ✓ miuix.os.DeviceHelper.isTinyScreen → false")
            }.onFailure { }

            // ⑤ miuix.device.DeviceUtils.isFlipTinyScreen → false(存在则 hook)
            runCatching {
                val cls = cl.loadClass("miuix.device.DeviceUtils")
                hook(cls.method("isFlipTinyScreen", Context::class.java), replaceResult(false))
                log("TinyScreenFix: ✓ miuix.device.DeviceUtils.isFlipTinyScreen → false")
            }.onFailure { }
        }
    }
}
