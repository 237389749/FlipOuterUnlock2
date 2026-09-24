package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 外屏 cutout 源头清零（flip1 + 属性层专用，2026-09-24 新增）。
 *
 * ── 为什么需要它（实测根因）──
 * Main.kt 原本只对 flip2 启用 cutout hook，理由（README/注释）是"flip1 由属性层覆盖"。
 * 该假设在国内版 HyperOS 2 成立，但在 **flip1 + 国际版 ruyi_global + 属性层** 下不成立：
 *
 *   实测外屏(displayId 0, 1208x1392) cutout 真实存在：
 *     mDisplayCutout = DisplayCutout{insets=Rect(398, 0 - 0, 0)}  sideHint=LEFT
 *                      boundingRect=[Rect(0, 0 - 398, 728), ...]
 *   第三方应用 ActivityRecord：
 *     mAppBounds           = Rect(398, 0 - 1208, 1392)   ← 左侧被切 398px
 *     areBoundsLetterboxed = true
 *     letterboxReason      = DISPLAY_CUTOUT              ← 命中 §6 判定第 3 条
 *     mGlobalScale         = 1.0 / initBoundsCompatController = -1.0
 *                          ← MIUI size-compat 链完全未启用，故 AppFullscreen 无效
 *
 * 即：命中的是 **AOSP DISPLAY_CUTOUT letterbox**（第 3 条），不是 MIUI_SIZE_COMPAT_MODE（第 4 条）；
 * 而处理第 3 条的两个 hook 恰好被 Main.kt 的 `isFlip2Device()` 挡在 flip1 之外。
 *
 * ── 为什么不用现成的 CutoutRemove ──
 * CutoutRemove.#1 hook 的是 `CutoutSpecification.Parser.parse(String)` —— **全局静态、无 display 上下文**，
 * 一旦挂钩会把内屏(displayId 8)的正常刘海 cutout(146px TOP)一并清零，
 * 触发 refMD §28.2 记录的 AOD NPE 崩溃链（DisplayUtils.getCutoutPosition 无 null 检查）。
 *
 * ── 本文件的 hook 点 ──
 * DisplayContent.calculateDisplayCutoutForRotation(int) —— **实例方法**，
 * 是 `mDisplayInfo.displayCutout` 的唯一设置源（DisplayContent L1674/L1807/L2230 三处调用
 * 均经此方法）。实例上可读 getDisplayId()，因此能精确只清外屏。
 *
 *   外屏(displayId==0) 的调用 → 返回 NO_CUTOUT
 *     → mDisplayInfo.displayCutout = null（L1689 isEmpty 分支）
 *     → DisplayFrames.update L57 getDisplayCutoutSafe 得全 bounds
 *     → DecorInsets mNonDecorFrame 不缩窄 → mAppBounds.left = 0（全屏）
 *   内屏(displayId!=0) 的调用 → 原样 proceed，146px 刘海保持
 *
 * 开关: 复用 persist.flipunlock.display.cutout（默认 true）。
 * 进程: system_server。
 */
object Flip1OuterCutoutHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayCutout) {
            log("Flip1OuterCutout: DISABLED by persist.flipunlock.display.cutout")
            return
        }
        log("Flip1OuterCutout: setting up (外屏 cutout 源头清零, flip1 属性层)")
        safeHook("Flip1OuterCutout") {
            hookCalculateDisplayCutoutForRotation(param.classLoader)
        }
    }

    // ── DisplayContent.calculateDisplayCutoutForRotation(int) → 外屏 NO_CUTOUT ──
    private fun hookCalculateDisplayCutoutForRotation(classLoader: ClassLoader) {
        runCatching {
            val dcClass = classLoader.loadClass("com.android.server.wm.DisplayContent")
            val method = dcClass.method("calculateDisplayCutoutForRotation",
                Int::class.javaPrimitiveType!!)
            // 热路径（DisplayFrames.update L2230 内每次都会调）→ 预先 resolve，避免 callMethod 的线性搜索
            val getDisplayId = dcClass.method("getDisplayId")
            // DisplayCutout.NO_CUTOUT（@hide, 编译期不可见）→ 反射取
            val cutoutClass = classLoader.loadClass("android.view.DisplayCutout")
            val noCutout = cutoutClass.getDeclaredField("NO_CUTOUT").let {
                it.isAccessible = true
                it.get(null)
            }
            var logged = false
            hook(method) { chain ->
                val displayId = getDisplayId.invoke(chain.thisObject) as? Int
                if (displayId == 0) {
                    if (!logged) {
                        logged = true
                        log("Flip1OuterCutout: ✓ 外屏(displayId=0) cutout → NO_CUTOUT")
                    }
                    noCutout
                } else {
                    chain.proceed()
                }
            }
            log("Flip1OuterCutout: ✓ calculateDisplayCutoutForRotation hooked (外屏 only)")
        }.onFailure { log("Flip1OuterCutout: calculateDisplayCutoutForRotation failed", it) }
    }
}
