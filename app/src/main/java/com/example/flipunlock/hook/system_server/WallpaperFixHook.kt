package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 壁纸尺寸钳制（2026-08-14 flip1 / 2026-08-21 flip2 重写）:
 * 修"背景一半黑"(flip2 属性层必现) + "开机桌面壁纸右侧黑"(flip1 偶发)。
 *
 * 【flip1 场景(偶发, §43.9)】: 内屏已拆但 display1(1080×2340) 仍枚举 → 壁纸引擎
 * (com.miui.miwallpaper) 开机竞态按"含内屏的多屏最大边" setDimensionHints(2340,2340)
 * → 壁纸按内屏比例生成, 显示在外屏(1208×1392)宽度不够 → 右侧黑。
 *
 * 【flip2 属性层场景(必现, §44 状态表实锤)】: 属性 1(multi_display_type=1) 伪装普通手机
 * → isFlipDevice=false → 壁纸引擎按"手机/内屏竖屏"比例 setDimensionHints(如 1080×2340
 * / 585×1392 / 1224×2912) → 外屏 1208×1392(近方形 0.87) 显示竖屏壁纸(0.42) →
 * 宽度只有屏幕一半 → "背景一半黑"(桌面 + 最近任务 recents 背景同黑)。
 *
 * 修复: hook WallpaperManagerService.setDimensionHints(int,int,String,int)（flip1/flip2
 * 服务端同构, flip2-services:2087）, 对 display0(外屏) 钳成**方形** MAX_DIM×MAX_DIM
 * —— 外屏 1208×1392 近方形, 方形壁纸 cover 缩放后全屏不黑; 不采用旧版逐边 minOf
 * (只压上限不补下限: 1080×2340→1080×1392 宽仍不足 → 依旧黑)。
 *
 * 触发条件(两个满足其一即钳):
 *   1) w > MAX_DIM || h > MAX_DIM    —— 引擎按内屏/手机大尺寸算(flip1 2340/flip2 2912)
 *   2) 比例明显偏离外屏(宽高比 < 0.75 或 > 1.4) —— 竖屏窄图(585×1392)两边都不超也钳
 * 正常场景引擎传 1208×1392(比例 0.868, 两边 ≤1392) → 不触发, 无副作用。
 *
 * 进程: system_server。
 */
object WallpaperFixHook {

    // flip1/flip2 外屏都是 1208×1392(§44 状态表), 最大边=1392; 换机型需调整
    private const val MAX_DIM = 1392

    fun hook(param: SystemServerStartingParam) {
        if (!Config.wallpaperFix) {
            log("WallpaperFix: DISABLED by persist.flipunlock.wallpaper.fix")
            return
        }
        log("WallpaperFix: setting up")
        safeHook("WallpaperFix") {
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wallpaper.WallpaperManagerService")
                val method = cls.method("setDimensionHints",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    Int::class.javaPrimitiveType!!)
                hook(method) { chain ->
                    val w = chain.args[0] as? Int ?: return@hook chain.proceed()
                    val h = chain.args[1] as? Int ?: return@hook chain.proceed()
                    val displayId = chain.args[3] as? Int ?: -1
                    val overLimit = w > MAX_DIM || h > MAX_DIM
                    val ratio = if (h > 0) w.toFloat() / h else 0f
                    val wrongRatio = ratio < 0.75f || ratio > 1.4f
                    if (displayId == 0 && (overLimit || wrongRatio)) {
                        log("WallpaperFix: ✓ clamp setDimensionHints ${w}x$h (d$displayId) -> ${MAX_DIM}x$MAX_DIM")
                        chain.proceed(arrayOf<Any?>(MAX_DIM, MAX_DIM, chain.args[2], chain.args[3]))
                    } else {
                        chain.proceed()
                    }
                }
                log("WallpaperFix: ✓ hooked WallpaperManagerService.setDimensionHints")
            }.onFailure { log("WallpaperFix: setDimensionHints failed: ${it.message}") }
        }
    }
}
