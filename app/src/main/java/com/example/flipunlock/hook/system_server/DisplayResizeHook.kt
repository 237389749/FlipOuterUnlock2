package com.example.flipunlock.hook.system_server

import android.view.DisplayInfo
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 外屏逻辑分辨率伪装(2026-08-15, flip2 system_server)。
 *
 * 背景(用户洞察): flip2 外屏 1208×1392(比例 0.868 接近方形), 伪装手机后系统/壁纸/
 *   相机按"手机"假设处理 → 壁纸缩放扩大、相机布局异常、UI 像"内屏半身"。
 *   目标: 让系统把外屏当作"1224 宽(≈内屏宽)的竖屏手机", 但**保持 1208:1392 比例**
 *   → 逻辑分辨率 1224×1410(1224/1410 = 0.8681 = 1208/1392, 无变形)。
 *
 * 技术点:
 * - 非整数缩放(1224/1208≈1.013)无功能影响: Android display 逻辑分辨率≠物理是常态,
 *   SurfaceFlinger 任意缩放; 比例一致是关键(无变形)。
 * - 只改 logicalWidth/logicalHeight(1224×1410), physical 保持 1208×1392
 *   (传感器/相机 physical 读取仍真实); logicalDensityDpi 保持 520(dp 尺寸几乎不变)。
 * - hook 点: LogicalDisplay.getDisplayInfoLocked() → displayId 0(外屏)时改 logical 尺寸。
 *   (DisplayInfo.logicalWidth/Height 是 public int, 直接赋值)
 *
 * ⚠️ 风险(用户已知): 全局 DisplayInfo 伪装影响所有 app 布局 + 折叠/展开切换时
 *   布局计算(flip2 真折叠设备) → 开关默认关闭, 实测后评估。
 * ⚠️ flip1 system_server 注入断路(§41.2) → 本 hook 仅 flip2 生效。
 *
 * 开关: persist.flipunlock.display.resize(默认 false)。
 */
object DisplayResizeHook {

    /** 目标逻辑分辨率: 宽 = 内屏宽 1224, 高按 1208:1392 比例。 */
    private const val LOGICAL_WIDTH = 1224
    private const val LOGICAL_HEIGHT = 1410

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayResize) {
            log("DisplayResizeHook: DISABLED by persist.flipunlock.display.resize")
            return
        }
        log("DisplayResizeHook: setting up (外屏 logical → ${LOGICAL_WIDTH}x$LOGICAL_HEIGHT)")
        safeHook("DisplayResizeHook") {
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.display.LogicalDisplay")
                val m = cls.method("getDisplayInfoLocked")
                hook(m, after { chain, result ->
                    val info = result as? DisplayInfo ?: return@after result
                    if (info.displayId == 0) {
                        info.logicalWidth = LOGICAL_WIDTH
                        info.logicalHeight = LOGICAL_HEIGHT
                        log("DisplayResizeHook: ✓ 外屏(displayId 0) logical → ${LOGICAL_WIDTH}x$LOGICAL_HEIGHT")
                    }
                    result
                })
                log("DisplayResizeHook: ✓ LogicalDisplay.getDisplayInfoLocked hooked")
            }.onFailure { log("DisplayResizeHook failed: ${it.message}") }
        }
    }
}
