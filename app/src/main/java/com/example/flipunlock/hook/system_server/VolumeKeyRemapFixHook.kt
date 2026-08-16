package com.example.flipunlock.hook.system_server

import android.content.Context
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 恢复 flip 折叠态音量键方向跟随旋转（2026-08-15 + 2026-08-16 #23 补强）。
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
 * 2026-08-16 (#23 实测仍不生效) 结构分析:
 *   MiInputKeyRemap 构造(76 行): supportVolumeKeyRemap()==true 才 registerRotationWatcher
 *     (rotation 信号源) + initDeviceId; hook 生效时(早于 initInternal)构造即注册 watcher ✓
 *   BaseMiuiPhoneWindowManager.initInternal(789-796): registerDisplayFoldListener + getInstance
 *   KeyRemapStatusSynchronizeHandler: mFoldStatus 初始 false —— flip2 恒折叠且从不展开时
 *     onDisplayFoldChanged 不回调(带 true) → notifyFoldStatus 永不触发 → 永不 remap(实测根因)
 *
 * 修复(三层):
 *   ① hook MiInputKeyRemap.supportVolumeKeyRemap()(静态) → true
 *     → BaseMiuiPhoneWindowManager 的 if 通过 + 构造里 watcher 注册(rotation 信号可用)
 *   ② hook MiInputKeyRemap.getInstance(Context) after → 主动初始化折叠态:
 *     设置 handler.mFoldStatus=true + 调 handleVolumeKeyRemap(true, 0) → 恒折叠设备立即 remap
 *   ③ hook MiInputKeyRemap.notifyFoldStatus(boolean) after → 折叠回调到来时同步字段 + 立即
 *     执行 handleVolumeKeyRemap(不等 handler 消息, 幂等安全)
 *
 * 进程: system_server(flip2 注入正常可生效; flip1 断路装不上, 无影响)。
 */
object VolumeKeyRemapFixHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.volumeKeyRemap) return
        log("VolumeKeyRemapFix: setting up")
        safeHook("VolumeKeyRemapFix") {
            // ① supportVolumeKeyRemap → true(336 门 + 构造注册 RotationWatcher)
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.input.MiInputKeyRemap")
                val m = cls.method("supportVolumeKeyRemap")
                hook(m, replaceResult(true))
                log("VolumeKeyRemapFix: ✓ supportVolumeKeyRemap → true (flip 音量键方向恢复)")
            }.onFailure { log("VolumeKeyRemapFix ① supportVolumeKeyRemap failed: ${it.message}") }

            // ② getInstance(Context) after: 主动初始化折叠态(flip2 恒折叠 → 立即 remap)
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.input.MiInputKeyRemap")
                val m = cls.method("getInstance", Context::class.java)
                hook(m, after { chain, result ->
                    val inst = result ?: return@after result
                    initFoldState(inst)
                    result
                })
                log("VolumeKeyRemapFix: ✓ hooked getInstance after [fold 主动初始化]")
            }.onFailure { log("VolumeKeyRemapFix ② getInstance failed: ${it.message}") }

            // ③ notifyFoldStatus(boolean) after: 折叠回调时同步字段 + 立即执行(兜底)
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.input.MiInputKeyRemap")
                val m = cls.method("notifyFoldStatus", Boolean::class.javaPrimitiveType!!)
                hook(m, after { chain, result ->
                    val inst = chain.thisObject ?: return@after result
                    val fold = chain.args[0] as? Boolean ?: return@after result
                    setFoldState(inst, fold)
                    result
                })
                log("VolumeKeyRemapFix: ✓ hooked notifyFoldStatus after [同步驱动]")
            }.onFailure { log("VolumeKeyRemapFix ③ notifyFoldStatus failed: ${it.message}") }
        }
    }

    /** 主动把折叠态置 true 并立即执行 handleVolumeKeyRemap(true, rotation=0) → 恒折叠设备开机即 remap。 */
    private fun initFoldState(inst: Any) {
        setFoldState(inst, true)
        runCatching {
            val m = inst.javaClass.method("handleVolumeKeyRemap",
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            m.invoke(inst, true, 0)
            log("VolumeKeyRemapFix: ✓ 主动 handleVolumeKeyRemap(true,0) → 音量键 remap 生效")
        }.onFailure { log("VolumeKeyRemapFix handleVolumeKeyRemap failed: ${it.message}") }
    }

    /** 同步 handler 内部类字段 mFoldStatus(供后续 rotation 消息正确判断), 并立即执行一次。 */
    private fun setFoldState(inst: Any, fold: Boolean) {
        runCatching {
            val handler = inst.javaClass.field("mHandler").get(inst) ?: return
            handler.javaClass.field("mFoldStatus").set(handler, fold)
            val m = inst.javaClass.method("handleVolumeKeyRemap",
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            val rotation = runCatching {
                handler.javaClass.field("mWindowRotation").get(handler) as? Int
            }.getOrNull() ?: 0
            m.invoke(inst, fold, rotation)
            log("VolumeKeyRemapFix: ✓ fold=$fold rotation=$rotation 同步 handleVolumeKeyRemap")
        }.onFailure { log("VolumeKeyRemapFix setFoldState failed: ${it.message}") }
    }
}
