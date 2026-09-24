package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 全 ALWAYS 模式（system_server 侧）：让窗口把挖孔区域视为可绘制区，不再因 cutout 避让而不全屏。
 *
 * 背景（国际版 ruyi_global + 属性层，2026-09-24 实测，refMD DisplayCutout.md §29）:
 *   persist.sys.multi_display_type=1 → MiuiMultiDisplayTypeInfo.isFlipDevice()=false
 *     → DisplayCutoutStubImpl.isFlipFolded()=false
 *       → WindowLayoutStubImpl 的 "isFlipFolded && layoutInDisplayCutoutMode==SHORT_EDGES → ALWAYS"
 *         兜底升级失效（该分支永不进入）
 *   → 第三方应用 attrs 原值原样透传。实测 dumpsys window windows:
 *       com.miui.home : layoutInDisplayCutoutMode=always      → 全屏
 *       com.taobao    : layoutInDisplayCutoutMode=shortEdges  → 左侧 398px 被避让
 *   → ActivityRecord 出现 areBoundsLetterboxed=true / letterboxReason=DISPLAY_CUTOUT，
 *     mAppBounds=Rect(398, 0 - 1208, 1392)（外屏 1208x1392 的物理挖孔在左）
 *
 * 与 app 端 identity/CutoutAlwaysHook 的关系:
 *   该文件 #4 已有同样的 getLayoutInDisplayCutoutMode → 3，但它是 wildcard app hook，
 *   只在 LSPosed 作用域内的进程安装；第三方应用不在作用域 → 拿不到。
 *   本文件补 system_server 侧，独立于 app 进程注入。
 *
 * Hook:
 *   #1 android.view.WindowLayoutStubImpl.getLayoutInDisplayCutoutMode(LayoutParams) → 3 (ALWAYS)
 *      窗口 frame 计算时跳过 cutout 裁剪（WindowLayout.computeFrames L68 cutoutMode==3 分支）
 *   #2 com.android.server.wm.WindowStateStubImpl.isMiuiLayoutInCutoutAlways(LayoutParams) → true
 *      WindowState.isLetterboxedForDisplayCutout() 第 3 条件短路 → 不产生 letterbox
 *      （小米预留豁免开关，原实现硬编码 return false）
 *
 * Toggle: persist.flipunlock.display.cutout（与 app 端 CutoutAlwaysHook 共用）
 * Process: system_server
 */
object CutoutAlways {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayCutout) {
            log("CutoutAlways: DISABLED by persist.flipunlock.display.cutout")
            return
        }
        log("CutoutAlways: setting up (system_server 侧全 ALWAYS)")
        safeHook("CutoutAlways") {
            hookLayoutInDisplayCutoutMode(param.classLoader)
            hookIsMiuiLayoutInCutoutAlways(param.classLoader)
        }
    }

    // ── #1 WindowLayoutStubImpl.getLayoutInDisplayCutoutMode → 3 (ALWAYS) ──
    private fun hookLayoutInDisplayCutoutMode(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("android.view.WindowLayoutStubImpl")
            val method = cls.method("getLayoutInDisplayCutoutMode",
                android.view.WindowManager.LayoutParams::class.java)
            hook(method, replaceResult(3))
            log("CutoutAlways: ✓ getLayoutInDisplayCutoutMode → 3 (ALWAYS)")
        }.onFailure { log("CutoutAlways: getLayoutInDisplayCutoutMode failed", it) }
    }

    // ── #2 WindowStateStubImpl.isMiuiLayoutInCutoutAlways → true ──
    private fun hookIsMiuiLayoutInCutoutAlways(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("com.android.server.wm.WindowStateStubImpl")
            val method = cls.method("isMiuiLayoutInCutoutAlways",
                android.view.WindowManager.LayoutParams::class.java)
            hook(method, replaceResult(true))
            log("CutoutAlways: ✓ isMiuiLayoutInCutoutAlways → true")
        }.onFailure { log("CutoutAlways: isMiuiLayoutInCutoutAlways failed", it) }
    }
}
