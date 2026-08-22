package com.example.flipunlock.hook.system_server

import android.content.Context
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 恢复 flip 折叠态音量键方向跟随旋转（2026-08-15 + 2026-08-16 #23 补强 + 2026-08-21 #23 方案2）。
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
 * 2026-08-21 (#23 方案2, refMD §44.6.2) FlipRes 全链补全:
 *   789 行静态门实锤: BaseMiuiPhoneWindowManager.<clinit> 的 IS_FOLD_DEVICE/IS_FLIP_DEVICE 是
 *     static final(属性1→双双 false) → registerDisplayFoldListener 不注册 → onDisplayFoldChanged
 *     永不回调 → ③(notifyFoldStatus after)永不触发 → ②(getInstance after)是唯一 remap 路径。
 *   方案2 = 给唯一路径加双保险, 不改变全局 isFlipDevice 语义:
 *     ④ hook notifyWindowRotation(int) after → rotation 信号到来时同步 mWindowRotation +
 *       立即执行 handleVolumeKeyRemap(不等 handler 消息, 幂等安全)
 *     ⑤ hook 私有构造(after) → 直接 thisObject 初始化折叠态(与 ② 触发点同在 initInternal:796,
 *       但更早更直接, 不依赖 getInstance 的返回值/同步)
 *
 * 2026-08-22 (#23 三 agent 深挖, refMD §44.6.4) 最终裁决层 ⑥:
 *   MiInputKeyRemap.handleVolumeKeyRemap(fold, rotation)(176-183) 是唯一裁决点:
 *     mVolumeHasRemap && (!fold || rotation!=0) → restoreVolumeKey()(恢复物理方向)
 *     !mVolumeHasRemap && fold && rotation==0   → remapVolumeKey()(互换 24↔25)
 *   → 仿 RotationFixHook ⑦-E 思路, 在最终执行点兜底: hook handleVolumeKeyRemap before,
 *     强制 fold=true, rotation 保留事件值(旋转已修好(⑦-E)后 rotation 事件真实发生) →
 *     竖屏(rotation==0)自动 remap、横屏(rotation!=0)自动 restore, 全自动跟随旋转。
 *   上游 isFlipDevice()→true 方案已否决(Agent2 审计): 副作用破坏已修好的旋转
 *     (DisplayRotation 937/958 强制竖屏) 且普通手机无 fold 事件 mFoldStatus 恒 false 仍不 remap。
 *
 * 修复(六层):
 *   ① hook MiInputKeyRemap.supportVolumeKeyRemap()(静态) → true
 *     → BaseMiuiPhoneWindowManager 的 if 通过 + 构造里 watcher 注册(rotation 信号可用)
 *   ② hook MiInputKeyRemap.getInstance(Context) after → 主动初始化折叠态:
 *     设置 handler.mFoldStatus=true + 调 handleVolumeKeyRemap(true, 0) → 恒折叠设备立即 remap
 *   ③ hook MiInputKeyRemap.notifyFoldStatus(boolean) after → 折叠回调到来时同步字段 + 立即
 *     执行 handleVolumeKeyRemap(不等 handler 消息, 幂等安全)
 *   ④ hook MiInputKeyRemap.notifyWindowRotation(int) after → rotation 信号到来时同步字段 +
 *     立即执行(弥补 watcher 未注册/消息延迟的 rotation 盲区)
 *   ⑤ hook MiInputKeyRemap 私有构造(after) → thisObject 主动初始化(双保险, 触发点同 ②)
 *   ⑥(2026-08-22 核心) hook MiInputKeyRemap.handleVolumeKeyRemap(boolean,int) before →
 *     强制 fold=true(属性1下 mFoldStatus 恒 false, fold 回调链断), rotation 保留事件值
 *     → 单点裁决全跟随: 竖屏 remap / 横屏 restore, 抹平 ②⑤ 硬编码 rotation=0 的竞态窗口,
 *       不依赖 mFoldStatus 字段, 幂等安全(与 ②③④⑤ 全部汇入同一入口, 无竞争)
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

            // ⑤ 私有构造(after): 直接 thisObject 主动初始化折叠态(双保险, 触发点=initInternal:796)
            //   ——不依赖 getInstance 返回值; 构造私有单例, 全生命周期仅触发一次, 幂等。
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.input.MiInputKeyRemap")
                val c = cls.getDeclaredConstructor(Context::class.java)
                    .also { it.isAccessible = true }
                hook(c, after { chain, result ->
                    val inst = chain.thisObject ?: return@after result
                    initFoldState(inst)
                    result
                })
                log("VolumeKeyRemapFix: ✓ hooked 构造 after [fold 主动初始化, 双保险]")
            }.onFailure { log("VolumeKeyRemapFix ⑤ 构造 failed: ${it.message}") }

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

            // ④ notifyWindowRotation(int) after: rotation 信号到来时同步字段 + 立即执行
            //   (原生只发 handler 消息 case2; 这里同步执行, 防消息延迟/丢失, 幂等安全)
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.input.MiInputKeyRemap")
                val m = cls.method("notifyWindowRotation", Int::class.javaPrimitiveType!!)
                hook(m, after { chain, result ->
                    val inst = chain.thisObject ?: return@after result
                    val rotation = chain.args[0] as? Int ?: return@after result
                    syncRotation(inst, rotation)
                    result
                })
                log("VolumeKeyRemapFix: ✓ hooked notifyWindowRotation after [rotation 同步]")
            }.onFailure { log("VolumeKeyRemapFix ④ notifyWindowRotation failed: ${it.message}") }

            // ⑥ handleVolumeKeyRemap(boolean,int) before: 最终裁决层兜底(2026-08-22, 三 agent 深挖)
            //   MiInputKeyRemap:176-183 是唯一裁决点:
            //     mVolumeHasRemap && (!fold || rotation!=0) → restoreVolumeKey()(恢复物理方向)
            //     !mVolumeHasRemap && fold && rotation==0   → remapVolumeKey()(互换 24↔25)
            //   属性1下 fold 回调链断(mFoldStatus 恒 false) → 强制 fold=true, rotation 保留事件值
            //   (旋转已修好(⑦-E)后 rotation 事件真实发生: watcher → notifyWindowRotation → ④
            //    syncRotation → 本方法) → 竖屏自动 remap / 横屏自动 restore, 全自动跟随。
            //   同时抹平 ②⑤ 硬编码 rotation=0 的竞态窗口; 与 ②③④⑤ 全部汇入同一入口, 无竞争;
            //   幂等(mVolumeHasRemap 状态机 + addKeyRemapping synchronized)。
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.input.MiInputKeyRemap")
                val m = cls.method("handleVolumeKeyRemap",
                    Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
                hook(m) { chain ->
                    val rotation = chain.args[1] as? Int ?: 0
                    val origFold = chain.args[0]
                    if (origFold != true) {
                        log("VolumeKeyRemapFix: ✓ handleVolumeKeyRemap fold→true rotation=$rotation")
                    }
                    chain.proceed(arrayOf<Any?>(true, rotation))
                }
                log("VolumeKeyRemapFix: ✓ hooked handleVolumeKeyRemap before [fold 强制 true, 单点裁决]")
            }.onFailure { log("VolumeKeyRemapFix ⑥ handleVolumeKeyRemap failed: ${it.message}") }
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

    /** 同步 handler 内部类字段 mWindowRotation, 并立即按当前 fold 执行一次(rotation 信号即时生效)。 */
    private fun syncRotation(inst: Any, rotation: Int) {
        runCatching {
            val handler = inst.javaClass.field("mHandler").get(inst) ?: return
            handler.javaClass.field("mWindowRotation").set(handler, rotation)
            val fold = runCatching {
                handler.javaClass.field("mFoldStatus").get(handler) as? Boolean
            }.getOrNull() ?: false
            val m = inst.javaClass.method("handleVolumeKeyRemap",
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            m.invoke(inst, fold, rotation)
            log("VolumeKeyRemapFix: ✓ rotation=$rotation fold=$fold 同步 handleVolumeKeyRemap")
        }.onFailure { log("VolumeKeyRemapFix syncRotation failed: ${it.message}") }
    }
}
