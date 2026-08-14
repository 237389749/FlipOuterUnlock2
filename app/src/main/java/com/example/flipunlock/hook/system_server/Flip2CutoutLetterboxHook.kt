package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * DISPLAY_CUTOUT letterbox 服务端解除（2026-08-14 从 Lite 移植重写, FLIP2 机型门）。
 *
 * refMD §34.3: flip2 应用不全屏的真凶 = AOSP DISPLAY_CUTOUT letterbox(非 MIUI size-compat):
 *   WindowState.isLetterboxedForDisplayCutout()(flip2-services):
 *     ① parentFrameWasClippedByDisplayCutout()   // app 进程 WindowLayout 上报"被挖孔裁剪"
 *     && ② layoutInDisplayCutoutMode != 3
 *     && ③ WindowStateStubImpl.isMiuiLayoutInCutoutAlways(attrs)   // ③ 豁免开关, flip2 硬编码 false
 *     && ④ isFullscreen() → letterbox
 *
 * 当前 CutoutRemove(清零 cutout 数据)在 flip2 上通过消除 ① 的触发条件间接解决;
 * 本 hook 做双保险: 候选1 直接打开 ③ 豁免开关(单点干净, 保留 cutout 数据);
 * 候选2 整个判定关闭(粗暴必达)。仅 FLIP2 生效(flip1 的 size-compat 链是主攻点)。
 *
 * 开关: 复用 persist.flipunlock.display.cutout(默认 true)。
 * 进程: system_server(flip2 zygisk 注入正常; flip1 断路装不上, 且机型门先挡)。
 */
object Flip2CutoutLetterboxHook {

    fun hook(param: SystemServerStartingParam) {
        if (!isFlip2Device()) {
            log("CutoutLetterboxFix: skip (FLIP2 only)")
            return
        }
        if (!Config.displayCutout) {
            log("CutoutLetterboxFix: DISABLED by persist.flipunlock.display.cutout")
            return
        }
        log("CutoutLetterboxFix: setting up (flip2)")
        safeHook("CutoutLetterboxFix") {
            // ── 候选 1: isMiuiLayoutInCutoutAlways → true（③ 豁免开关, 单点干净）──
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.WindowStateStubImpl")
                val lp = param.classLoader.loadClass("android.view.WindowManager\$LayoutParams")
                val m = cls.method("isMiuiLayoutInCutoutAlways", lp)
                hook(m, replaceResult(true))
                log("CutoutLetterboxFix: ✓ candidate1 isMiuiLayoutInCutoutAlways → true")
            }.onFailure { log("CutoutLetterboxFix: candidate1 failed: ${it.message}") }

            // ── 候选 2: isLetterboxedForDisplayCutout → false（AOSP 判定直接关, 必达）──
            runCatching {
                val ws = param.classLoader.loadClass("com.android.server.wm.WindowState")
                val m = ws.method("isLetterboxedForDisplayCutout")
                hook(m, replaceResult(false))
                log("CutoutLetterboxFix: ✓ candidate2 isLetterboxedForDisplayCutout → false")
            }.onFailure { log("CutoutLetterboxFix: candidate2 failed: ${it.message}") }
        }
    }
}
