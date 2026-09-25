package com.example.flipunlock.hook.ime

import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 输入法底部那条 48dp 导航栏（`⌄` 隐藏键盘 / `🌐` 切换输入法）与底部留白 → 去掉（2026-09-25）。
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
 *              mNavigationBarFrame = new NavigationBarFrame(mService);
 *              LayoutInflater.inflate(R.layout.input_method_navigation_bar, mNavigationBarFrame);   // ⌄ / 🌐
 *              decorView.addView(mNavigationBarFrame, new FrameLayout.LayoutParams(-1, systemInsets.bottom, 80));
 *       :466 getImeCaptionBarHeight() = getDimensionPixelSize(R.dimen.navigation_bar_frame_height)  // 48dp = 156px
 *       :183 lambda$...$0: visible = insets.isVisible(WindowInsets.Type.captionBar());
 *              mNavigationBarFrame.setVisibility(visible ? 0 : 8);
 *
 * 设备实测（ruyi_global 外屏 1208x1392 @3.25）:
 *   - `cmd overlay lookup android android:dimen/navigation_bar_frame_height` = 48.0dip = 156px
 *   - `InsetsSource id=22 type=captionBar frame=[0,1236][1208,1392] visible=true`（= 156px, 与上者同高）
 *   - IME 窗口 fitTypes = `STATUS_BARS NAVIGATION_BARS`（不含 CAPTION_BAR）
 *   - ① 只把 NavigationBarFrame 高度归 0 后: ⌄/🌐 消失, 但键盘底部仍空 ~177px
 *     （截图最低内容行 y=1215, 屏幕底 1392）⇒ 底部留白来自 captionBar 这个"系统栏" inset, 不是 Frame 占位
 *
 * 修复（两步, 都在输入法进程内, 纯运行时无持久化副作用）:
 *   ① NavigationBarController$Impl.installNavigationBarFrameIfNecessary() after →
 *      mNavigationBarFrame 的 LayoutParams.height = 0 + setVisibility(GONE)
 *   ② WindowInsets 的 captionBar 相关读取 → 对输入法隐藏 captionBar（底部 156px 的留白来源）:
 *      getInsets(mask) / getInsetsIgnoringVisibility(mask) 剔除 captionBar 的部分,
 *      isVisible(mask) 含 captionBar 时返回 false（带日志, 用于确认输入法确实读它）
 *   之所以不选其它点:
 *     - getImeCaptionBarHeight() → 0 无效: 走 :150 分支时高度取 systemInsets.bottom
 *     - system 侧 captionBar / config_navBarHeight / navbar overlay 均无效: 本机外屏没有系统导航栏（实测已证）
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

    /** 防递归：hook 内部要读 captionBar 的原始值时会再次进入自己的 hook。 */
    private val readingCaption = ThreadLocal<Boolean>()

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.imeNavBarFix) {
            log("ImeNavBarFix: DISABLED by persist.flipunlock.ime.navbar")
            return
        }
        val cl = processClassLoader(param.classLoader)

        // ── ① 去掉 NavigationBarFrame（⌄ / 🌐 那个 48dp Frame）──
        safeHook("ImeNavBarFix/frame") {
            val cls = cl.findClassUp(IMPL_CLASS)
            if (cls == null) {
                log("ImeNavBarFix: $IMPL_CLASS 找不到（该进程不是输入法实现?）, skip")
            } else {
                val install = runCatching { cls.method("installNavigationBarFrameIfNecessary") }.getOrNull()
                if (install == null) {
                    log("ImeNavBarFix: installNavigationBarFrameIfNecessary 找不到, skip")
                } else {
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

        // ── ② 对输入法隐藏 captionBar（底部那块留白的来源）──
        safeHook("ImeNavBarFix/insets") {
            val captionBar = runCatching { WindowInsets.Type.captionBar() }.getOrNull()
            if (captionBar == null) {
                log("ImeNavBarFix: WindowInsets.Type.captionBar() 不可用, skip insets 部分")
                return@safeHook
            }
            var logged = 0
            for (name in listOf("getInsets", "getInsetsIgnoringVisibility")) {
                val m = runCatching {
                    WindowInsets::class.java.method(name, Int::class.javaPrimitiveType!!)
                }.getOrNull() ?: continue
                hook(m, after { chain, result ->
                    val mask = runCatching { chain.args[0] as? Int }.getOrNull() ?: return@after result
                    if (mask and captionBar == 0) return@after result
                    if (readingCaption.get() == true) return@after result
                    val wi = chain.thisObject as? WindowInsets ?: return@after result
                    val insets = result as? Insets ?: return@after result
                    readingCaption.set(true)
                    val cap = runCatching { wi.getInsetsIgnoringVisibility(captionBar) }.getOrNull()
                    readingCaption.remove()
                    if (cap == null) return@after result
                    if (logged < 8) {
                        logged++
                        log("ImeNavBarFix: $name(mask=0x${Integer.toHexString(mask)}) caption=${cap} → 剔除")
                    }
                    Insets.of(
                        (insets.left - cap.left).coerceAtLeast(0),
                        (insets.top - cap.top).coerceAtLeast(0),
                        (insets.right - cap.right).coerceAtLeast(0),
                        (insets.bottom - cap.bottom).coerceAtLeast(0),
                    )
                })
                log("ImeNavBarFix: ✓ hooked WindowInsets.$name")
            }
            runCatching {
                hook(WindowInsets::class.java.method("isVisible", Int::class.javaPrimitiveType!!),
                    after { chain, result ->
                        val mask = runCatching { chain.args[0] as? Int }.getOrNull() ?: return@after result
                        if (mask and captionBar != 0 && result == true) false else result
                    })
                log("ImeNavBarFix: ✓ hooked WindowInsets.isVisible")
            }.onFailure { log("ImeNavBarFix: hook isVisible 失败: ${it.message}") }
        }
    }
}
