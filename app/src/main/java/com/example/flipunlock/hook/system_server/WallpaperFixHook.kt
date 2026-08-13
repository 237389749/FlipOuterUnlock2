package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 壁纸尺寸钳制（2026-08-14）: 修"开机后桌面壁纸右侧黑"(偶发)。
 *
 * 现象(用户实测): 开机后桌面壁纸"左边有、右边黑"(像壁纸宽度不够被黑填充);
 * 上滑进最近任务再回桌面 → 重绘后正常; 装模块前纯原生也有(非模块引入)。
 *
 * 根因: flip1 内屏已拆但 display 1(1080×2340)仍枚举(§39 拓扑)。
 * 壁纸引擎(com.miui.miwallpaper)开机竞态时按"含内屏的多屏最大边"算壁纸尺寸,
 * setDimensionHints(2340,2340) → 壁纸按 2340 高/内屏比例生成, 显示在外屏
 * (1208×1392)宽度不够 → 右侧黑; 引擎正常时按外屏算 → 不黑。
 * dumpsys wallpaper: display0 mWidth=2340×2340 只可能来自客户端 setDimensionHints
 * (服务端 ensureSaneWallpaperDisplaySize 对 display0 只会兜底到 1392)。
 *
 * 修复: hook WallpaperManagerService.setDimensionHints(int,int,String,int),
 * 把 display 0 的 width/height 钳到外屏最大边, 引擎即按外屏尺寸生成壁纸。
 *
 * 进程: system_server。
 */
object WallpaperFixHook {

    // flip1 外屏 1208×1392 的最大边; 换机型(如 flip2 外屏尺寸不同)需调整
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
                    if (displayId == 0 && (w > MAX_DIM || h > MAX_DIM)) {
                        val nw = minOf(w, MAX_DIM)
                        val nh = minOf(h, MAX_DIM)
                        log("WallpaperFix: ✓ clamp setDimensionHints ${w}x$h (d$displayId) -> ${nw}x$nh")
                        chain.proceed(arrayOf<Any?>(nw, nh, chain.args[2], chain.args[3]))
                    } else {
                        chain.proceed()
                    }
                }
                log("WallpaperFix: ✓ hooked WallpaperManagerService.setDimensionHints")
            }.onFailure { log("WallpaperFix: setDimensionHints failed: ${it.message}") }
        }
    }
}
