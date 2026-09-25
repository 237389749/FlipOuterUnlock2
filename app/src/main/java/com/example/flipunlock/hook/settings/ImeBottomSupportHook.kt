package com.example.flipunlock.hook.settings

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 设置「全面屏键盘优化」入口 — 解除国际版硬隐藏（2026-09-24）。
 *
 * 现象（用户实测）: ruyi_global 的「语言与输入法」里没有「全面屏键盘优化」入口, 国行版同位置有。
 *
 * 根因（ruyi_global Settings.apk 反编译实锤, 三处）:
 *   ① MiuiLanguageAndInputSettings.onCreate(:318-324):
 *        this.sMiuiImeBottomSupport = InputMethodFunctionSelectUtils.isMiuiImeBottomSupport();
 *        PreferenceCategory pc = findPreference("full_screen_keyboard_optimization");
 *        if (this.sMiuiImeBottomSupport) { mBottomAddPref = findPreference("miui_bottom_manager"); }
 *        else { getPreferenceScreen().removePreference(pc); }      // ← 整块分类移除(非置灰)
 *   ② InputMethodFunctionSelectUtils.isMiuiImeBottomSupport():
 *        SystemProperties.getInt("ro.miui.support_miui_ime_bottom", 0) == 1
 *          && !Build.IS_INTERNATIONAL_BUILD                        // import miui.os.Build
 *          && isFullScreenDevice();
 *   ③ miui.os.Build.IS_INTERNATIONAL_BUILD =
 *        SystemProperties.get("ro.product.mod_device", "").contains("_global");
 *
 * 设备实测（345299d / ruyi_global / OS3.0.303.0.WNIMIXM）:
 *   ro.product.mod_device=ruyi_global      → IS_INTERNATIONAL_BUILD = true
 *   ro.miui.support_miui_ime_bottom        → 未设置(getInt→0)
 *   → ② 前两个条件皆 false → 入口必然隐藏。属 ROM 国际版硬编码,
 *     与 flip 属性层 / 本模块无关（Settings 进程此前根本不在 LSPosed scope 内）。
 *
 * 修复: hook isMiuiImeBottomSupport() → true（单点覆盖三个条件, 纯运行时, 无持久化副作用）。
 *   属性层路线不可行: IS_INTERNATIONAL_BUILD 是 static final（类加载固化, resetprop 已来不及）,
 *   只能改 ro.product.mod_device 去掉 _global —— 会翻转整个 ROM 的国行/国际分支, 不可取。
 *
 * ⚠️ 生效前提: LSPosed 作用域必须勾选 com.android.settings（默认不在 scope），勾选后重启。
 * ⚠️ 功能实效: 该入口页 = InputMethodFullScreenManager（R.xml.full_keyboard_settings,
 *   仅配置 左/中/右 功能键 + 多功能键盘开关 enable_miui_ime_bottom_view）。真正消费方是
 *   MIUI 定制输入法（InputMethodFunctionSelectUtils.sCustomIme = com.sohu.inputmethod.sogou.xiaomi /
 *   com.iflytek.inputmethod.miui / com.baidu.input_mi / com.xiaomi.type）+ InputMethodBottomManager
 *   （从 com.miui.phrase 动态加载）。只装 Gboard 时入口能显示/能点, 但键盘底栏不会出现。
 *
 * 开关: persist.flipunlock.settings.imebottom（默认 true）
 */
object ImeBottomSupportHook : BaseHook() {

    override val targetPackages = listOf("com.android.settings")

    private const val UTILS_CLASS = "com.android.settings.inputmethod.InputMethodFunctionSelectUtils"

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.settingsImeBottom) {
            log("ImeBottomSupport: DISABLED by persist.flipunlock.settings.imebottom")
            return
        }
        val cl = processClassLoader(param.classLoader)
        safeHook("ImeBottomSupport") {
            val cls = cl.findClassUp(UTILS_CLASS)
            if (cls == null) {
                log("ImeBottomSupport: $UTILS_CLASS 找不到, skip")
                return@safeHook
            }
            hook(cls.method("isMiuiImeBottomSupport"), replaceResult(true))
            log("ImeBottomSupport: ✓ $UTILS_CLASS.isMiuiImeBottomSupport → true (「全面屏键盘优化」入口恢复)")
        }
    }
}
