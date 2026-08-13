package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 方向修复（2026-08-12 重写）：hook MiuiOrientationImpl.getOrientationMode。
 *
 * 根因（refMD §43.2.1，flip2-miui-services 反编译实锤）：
 *   MiuiOrientationImpl.getOrientationMode(ActivityRecord, int)：
 *     if (isDisplayFolded(r) && !isFlipDevice()) return -1;   ← 属性 1(isFlipDevice=false)折叠态不干预
 *     else if (isFlipDevice()) { ... mode = 3; }              ← 原生 flip 折叠态: MODE_FLIP_OUTSIDE_ORIENTATION(3)
 *   → 属性层伪装手机后，外屏折叠态的 MODE_FLIP_OUTSIDE_ORIENTATION 不再提供 → launcher/外屏 app
 *     回退 manifest portrait → 锁竖屏（§43.2.1）。
 *
 * 旧方案（setUserRotation LOCKED→FREE）只解决"用户旋转模式被锁"，且副作用=拦控制中心磁贴
 * 切锁定（§43.1）。本方案直接恢复外屏折叠旋转模式，不碰 setUserRotation → 磁贴不受影响。
 *
 * 修复：hook getOrientationMode(ActivityRecord,int)，原结果 -1（折叠+非 flip 被 return -1 的分支）
 *   且目标在 displayId 0（外屏）→ 强制返回 3（MODE_FLIP_OUTSIDE_ORIENTATION）。
 *   非折叠/内屏/虚拟屏保持原逻辑（原 return -1 的 isEmbedded/虚拟屏分支不受影响）。
 *
 * 进程：system_server。
 */
object RotationFixHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.rotationFix) {
            log("RotationFix: DISABLED by persist.flipunlock.rotation.fix")
            return
        }
        log("RotationFix: setting up")
        safeHook("RotationFix") {
            // ── MiuiOrientationImpl.getOrientationMode(ActivityRecord, int) ──
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.MiuiOrientationImpl")
                val arCls = param.classLoader.loadClass("com.android.server.wm.ActivityRecord")
                val method = cls.method("getOrientationMode", arCls, Int::class.javaPrimitiveType!!)
                hook(method, after { chain, result ->
                    val mode = result as? Int ?: -1
                    if (mode != -1) return@after mode
                    // 原逻辑 return -1：折叠 + 非 flip（我们要修的）或 isEmbedded/虚拟屏（不动）
                    val r = chain.args[0]
                    val displayId = runCatching {
                        val dc = r?.javaClass?.getMethod("getDisplayContent")?.invoke(r)
                        dc?.javaClass?.getField("mDisplayId")?.get(dc) as? Int
                    }.getOrNull()
                    if (displayId == 0) {
                        log("RotationFix: ✓ getOrientationMode -1 → 3 (FLIP_OUTSIDE, display0 外屏折叠态)")
                        3
                    } else {
                        mode
                    }
                })
                log("RotationFix: ✓ hooked MiuiOrientationImpl.getOrientationMode(ActivityRecord,int)")
            }.onFailure { log("RotationFix: MiuiOrientationImpl.getOrientationMode failed: ${it.message}") }
        }
    }
}
