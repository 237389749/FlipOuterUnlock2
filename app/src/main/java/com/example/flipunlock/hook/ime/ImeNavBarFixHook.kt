package com.example.flipunlock.hook.ime

import android.view.View
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 输入法底部那条 48dp 导航栏（`⌄` 隐藏键盘 / `🌐` 切换输入法）→ 高度归 0（2026-09-25）。
 *
 * 现象: 键盘弹出时底部始终有一条约 48dp 的区域，把键盘往上顶；里面的两个按钮
 *   （左 `⌄` 隐藏键盘、右 `🌐` 切换输入法）与「全面屏键盘优化」无关，换任何输入法都一样。
 *
 * 根因（framework 反编译实锤, refMD IME_Restrictions §8.7.2）:
 *   绘制者 = 输入法进程里的 AOSP 组件, 不是 SystemUI / miuihome（本机外屏连系统导航栏窗口都没有）:
 *     android.inputmethodservice.InputMethodService:207
 *       private final NavigationBarController mNavigationBarController = new NavigationBarController(this);
 *     android.inputmethodservice.NavigationBarController$Impl:
 *       :134 installNavigationBarFrameIfNecessary()
 *              if (!mImeDrawsImeNavBar || mNavigationBarFrame != null) return;
 *              mNavigationBarFrame = new NavigationBarFrame(mService);
 *              LayoutInflater.inflate(R.layout.input_method_navigation_bar, mNavigationBarFrame);   // ⌄ / 🌐
 *              decorView.addView(mNavigationBarFrame, new FrameLayout.LayoutParams(-1, systemInsets.bottom, 80));
 *       :466 getImeCaptionBarHeight() = getDimensionPixelSize(R.dimen.navigation_bar_frame_height)  // 48dp = 156px
 *       :183 lambda$...$0: visible = insets.isVisible(WindowInsets.Type.captionBar());
 *              mNavigationBarFrame.setVisibility(visible ? 0 : 8);
 *       :205 updateInsets(): if (frame 不可见) return;   ⇒ Frame 一旦不可见/高度 0, IME 不再为它让位
 *   设备实测: `cmd overlay lookup android android:dimen/navigation_bar_frame_height` = 48.0dip = 156px @3.25,
 *     与截图中底部那块 156px 完全一致; captionBar inset [0,1236][1208,1392] visible=true 同高同位。
 *
 * 修复: hook NavigationBarController$Impl.installNavigationBarFrameIfNecessary() 之后, 把
 *   mNavigationBarFrame 的 LayoutParams.height 置 0 并 setVisibility(GONE)（纯运行时, 无持久化副作用）。
 *   之所以不选其它点:
 *     - getImeCaptionBarHeight() → 0 无效: 走 :150 分支时高度取 systemInsets.bottom（systemBars 含 captionBar）
 *     - 系统侧 captionBar / config_navBarHeight / navbar overlay 均无效: 本机外屏没有系统导航栏（实测已证）
 *     - RRO overlay 覆盖 navigation_bar_frame_height 也可行, 但需要 Android SDK/aapt2 构建 overlay APK
 *
 * ⚠️ 生效前提: 目标输入法包必须在 LSPosed 作用域内（本模块 scope 默认不含输入法）, 勾选后重启对应输入法进程。
 * ⚠️ 代价: 键盘底部不再有「隐藏键盘 / 切换输入法」两个按钮。
 *
 * 开关: persist.flipunlock.ime.navbar（默认 true）
 */
object ImeNavBarFixHook : BaseHook() {

    // 主流输入法包名（framework 里这段代码对所有 IME 都一样；装了别的输入法把包名加进来即可）
    override val targetPackages = listOf(
        "com.google.android.inputmethod.latin", // Gboard
        "com.android.inputmethod.latin",        // AOSP LatinIME
        "com.sohu.inputmethod.sogou.xiaomi",    // 小米版搜狗
        "com.sohu.inputmethod.sogou",           // 搜狗
        "com.iflytek.inputmethod.miui",         // 小米版讯飞
        "com.iflytek.inputmethod",              // 讯飞
        "com.baidu.input_mi",                   // 小米版百度
        "com.baidu.input",                      // 百度
        "com.xiaomi.type",                      // 小米输入法
        "com.tencent.wetype",                   // 微信输入法
    )

    private const val IMPL_CLASS = "android.inputmethodservice.NavigationBarController\$Impl"

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.imeNavBarFix) {
            log("ImeNavBarFix: DISABLED by persist.flipunlock.ime.navbar")
            return
        }
        val cl = processClassLoader(param.classLoader)
        safeHook("ImeNavBarFix") {
            val cls = cl.findClassUp(IMPL_CLASS)
            if (cls == null) {
                log("ImeNavBarFix: $IMPL_CLASS 找不到（该进程不是输入法实现?）, skip")
                return@safeHook
            }
            val install = runCatching { cls.method("installNavigationBarFrameIfNecessary") }.getOrNull()
            if (install == null) {
                log("ImeNavBarFix: installNavigationBarFrameIfNecessary 找不到, skip")
                return@safeHook
            }
            hook(install, after { chain, result ->
                val impl = chain.thisObject ?: return@after result
                val frame = runCatching { impl.getField("mNavigationBarFrame") }.getOrNull() as? View
                    ?: return@after result
                if (frame.visibility != View.GONE) frame.visibility = View.GONE
                val lp = frame.layoutParams
                if (lp != null && lp.height != 0) {
                    val oldHeight = lp.height
                    lp.height = 0
                    frame.layoutParams = lp
                    log("ImeNavBarFix: ✓ NavigationBarFrame 高度 $oldHeight → 0")
                }
                result
            })
            log("ImeNavBarFix: ✓ hooked $IMPL_CLASS.installNavigationBarFrameIfNecessary")
        }
    }
}
