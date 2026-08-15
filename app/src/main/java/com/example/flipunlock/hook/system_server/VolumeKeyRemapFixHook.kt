package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 恢复 flip 折叠态音量键方向跟随旋转（2026-08-15）。
 *
 * 背景（flip2-miui-services 反编译实锤）:
 *   BaseMiuiPhoneWindowManager:336
 *     if (MiInputKeyRemap.supportVolumeKeyRemap()) {   ← 属性 1 → false → 整个功能不启动!
 *         mMiInputKeyRemap.notifyFoldStatus(folded);
 *     }
 *   MiInputKeyRemap.supportVolumeKeyRemap() = MiuiMultiDisplayTypeInfo.isFlipDevice()
 *     → 属性 1(伪装手机) → false → 音量键重映射完全禁用。
 *
 *   flip 原生行为(属性 4): MiInputKeyRemap.handleVolumeKeyRemap(fold, rotation)
 *     - fold=true && rotation==0(折叠+外屏竖屏) → remapVolumeKey(): 互换 KEYCODE_VOLUME_UP(24)
 *       与 VOLUME_DOWN(25) → 音量键方向跟随屏幕(物理方向 vs 屏幕方向相反)
 *     - 非折叠 或 rotation!=0 → restoreVolumeKey() 恢复物理方向
 *   → "音量键功能跟随方向旋转而改变"(用户要恢复的功能)。
 *
 * 修复: hook MiInputKeyRemap.supportVolumeKeyRemap()(静态) → true
 *   → BaseMiuiPhoneWindowManager 的 if 通过 → notifyFoldStatus/notifyWindowRotation
 *     正常驱动 handleVolumeKeyRemap → 折叠态音量键互换恢复。
 *   mMiInputKeyRemap 无条件初始化(796 行 getInstance), 无 NPE 风险。
 *   handleVolumeKeyRemap 内部不读 isFlipDevice, 只看 fold/rotation → hook 足够。
 *
 * 进程: system_server(flip2 注入正常可生效; flip1 断路装不上, 无影响)。
 */
object VolumeKeyRemapFixHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.enabled) return
        log("VolumeKeyRemapFix: setting up")
        safeHook("VolumeKeyRemapFix") {
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.input.MiInputKeyRemap")
                val m = cls.method("supportVolumeKeyRemap")
                hook(m, replaceResult(true))
                log("VolumeKeyRemapFix: ✓ supportVolumeKeyRemap → true (flip 音量键方向恢复)")
            }.onFailure { log("VolumeKeyRemapFix failed: ${it.message}") }
        }
    }
}
