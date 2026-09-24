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
 * 2026-08-22 用户反馈(内外屏切换场景): "音量机制不生效, 估计走了内屏模式" —— flip2 实际
 *   内外屏频繁切换(非恒折叠)。v1 无条件 fold=true 在展开态(内屏)会错误 remap; 且折叠态
 *   (外屏)不生效 = handleVolumeKeyRemap 未被驱动(fold 回调链断)。
 *   FlipRes 实锤: DisplayFoldController(flip2-services/policy, 构造无条件注册
 *   DeviceStateManager.FoldStateListener[41-47]) → setDeviceFolded[67] 是折叠状态更新汇聚点,
 *   属性1下真实触发(物理 DeviceState 驱动, 与 isFlipDevice 无关) → mFolded[98] 真实;
 *   WindowManagerServiceImpl.isDeviceStateFolded[3005] = mFoldedDeviceStates 含 mCurrentDeviceState
 *   → 真实折叠状态可用。改造: ⑥ v2 fold=真实折叠状态(反射 WindowManagerServiceStub.get().
 *   isDeviceStateFolded()) → 折叠(外屏)remap / 展开(内屏)restore; ⑦ 新 hook
 *   DisplayFoldController.setDeviceFolded(boolean) after → 内外屏切换主动驱动
 *   handleVolumeKeyRemap(新 fold, 当前 rotation)(原生 fold 回调链被 789 静态门挡死)。
 *
 * 修复(七层):
 *   ① hook MiInputKeyRemap.supportVolumeKeyRemap()(静态) → true
 *     → BaseMiuiPhoneWindowManager 的 if 通过 + 构造里 watcher 注册(rotation 信号可用)
 *   ② hook MiInputKeyRemap.getInstance(Context) after → 主动初始化折叠态:
 *     设置 handler.mFoldStatus=true + 调 handleVolumeKeyRemap(true, 0) → 恒折叠设备立即 remap
 *   ③ hook MiInputKeyRemap.notifyFoldStatus(boolean) after → 折叠回调到来时同步字段 + 立即
 *     执行 handleVolumeKeyRemap(不等 handler 消息, 幂等安全)
 *   ④ hook MiInputKeyRemap.notifyWindowRotation(int) after → rotation 信号到来时同步字段 +
 *     立即执行(弥补 watcher 未注册/消息延迟的 rotation 盲区)
 *   ⑤ hook MiInputKeyRemap 私有构造(after) → thisObject 主动初始化(双保险, 触发点同 ②)
 *   ⑥(核心) hook MiInputKeyRemap.handleVolumeKeyRemap(boolean,int) before → fold = 真实折叠
 *     状态(反射 WindowManagerServiceStub.get().isDeviceStateFolded(), 物理 DeviceState 驱动),
 *     rotation 保留事件值 → 单点裁决: 折叠+竖屏 remap / 展开或横屏 restore, 内外屏切换自动
 *     跟随; 抹平 ②⑤ 硬编码 rotation=0 的竞态窗口, 幂等安全(与 ②③④⑤ 汇入同一入口, 无竞争)
 *   ⑦(2026-08-22 内外屏切换驱动) hook DisplayFoldController.setDeviceFolded(boolean) after →
 *     折叠↔展开切换时主动调 handleVolumeKeyRemap(新 fold, 当前 rotation) → 立即 remap/restore
 *     (原生 fold 回调链断, 无此驱动则切换后不更新; ②⑤ 记录的 mirkInstance 直接驱动)
 *
 * 进程: system_server。
 *   ⚠️ 2026-09-24 订正: 原文写「flip1 断路装不上, 无影响」——**该判断已作废**。
 *   同进程的 RotationFixHook 在 flip1 ruyi_global 上**实测生效**(refMD §43.15 四证据:
 *   Main.kt 无注释 / 属性缺省 true / 日志实时打 ⑦-E+④ / 行为侧 accelerometer_rotation=1)。
 *   按 refMD §41.2「无日志 ≠ 未注入」铁律, flip1 的 system_server 注入是**可靠的**,
 *   只是 onSystemServerStarting 日志可能不出。故本 hook 在 flip1 上也应生效, 待装机验证。
 */
object VolumeKeyRemapFixHook {

    /** 最近一次 MiInputKeyRemap 实例(②⑤ after 时记录), 供 ⑦ 折叠切换直接驱动 handleVolumeKeyRemap。 */
    @Volatile
    private var mirkInstance: Any? = null

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
                    mirkInstance = inst
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
                    mirkInstance = inst
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

            // ⑥ handleVolumeKeyRemap(boolean,int) before: 最终裁决层(2026-08-22 三 agent 深挖)
            //   MiInputKeyRemap:176-183 是唯一裁决点:
            //     mVolumeHasRemap && (!fold || rotation!=0) → restoreVolumeKey()(恢复物理方向)
            //     !mVolumeHasRemap && fold && rotation==0   → remapVolumeKey()(互换 24↔25)
            //   v1(31619bb)无条件 fold=true; v2(2026-08-22 用户反馈内外屏切换场景)改为
            //   fold = **真实折叠状态**(反射 WindowManagerServiceStub.get().isDeviceStateFolded(),
            //   基于 mCurrentDeviceState 物理 DeviceState, 属性1下真实) → 折叠(外屏)remap、
            //   展开(内屏)restore, 内外屏切换自动跟随。rotation 保留事件值。
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.input.MiInputKeyRemap")
                val m = cls.method("handleVolumeKeyRemap",
                    Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
                hook(m) { chain ->
                    val rotation = chain.args[1] as? Int ?: 0
                    val folded = realFoldState(param.classLoader)
                    if (chain.args[0] != folded) {
                        log("VolumeKeyRemapFix: ✓ handleVolumeKeyRemap fold ${chain.args[0]}→$folded rotation=$rotation")
                    }
                    chain.proceed(arrayOf<Any?>(folded, rotation))
                }
                log("VolumeKeyRemapFix: ✓ hooked handleVolumeKeyRemap before [fold=真实折叠状态, 单点裁决]")
            }.onFailure { log("VolumeKeyRemapFix ⑥ handleVolumeKeyRemap failed: ${it.message}") }

            // ⑦ DisplayFoldController.setDeviceFolded(boolean) after: 内外屏切换立即驱动 remap
            //   DisplayFoldController(flip2-services/policy, 构造无条件注册 DeviceStateManager.
            //   FoldStateListener[41-47]) → setDeviceFolded[67] 是折叠状态更新汇聚点, 属性1下
            //   真实触发(物理 DeviceState 驱动, 与 isFlipDevice 无关) → mFolded 字段[98]真实。
            //   hook after: 折叠↔展开切换时主动调 MiInputKeyRemap.handleVolumeKeyRemap(新 fold,
            //   当前 rotation) → ⑥ 入口统一裁决 → 外屏 remap / 内屏 restore 即时生效
            //   (原生 fold 回调链被 789 静态门挡死, 无此驱动则切换后不更新)。
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.policy.DisplayFoldController")
                val m = cls.method("setDeviceFolded", Boolean::class.javaPrimitiveType!!)
                hook(m, after { chain, result ->
                    val fold = chain.args[0] as? Boolean ?: return@after result
                    triggerRemap(fold)
                    result
                })
                log("VolumeKeyRemapFix: ✓ hooked DisplayFoldController.setDeviceFolded after [折叠切换驱动]")
            }.onFailure { log("VolumeKeyRemapFix ⑦ setDeviceFolded failed: ${it.message}") }
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

    /** 真实折叠状态: 反射 WindowManagerServiceStub.get().isDeviceStateFolded()。
     *  (WindowManagerServiceImpl:3005 = mFoldedDeviceStates 含 mCurrentDeviceState, 物理
     *  DeviceState 驱动, 属性1下真实; stub get() 是 package-private static, libxposed 可及)。
     *  失败回退 true(恒折叠保守: 外屏优先 remap)。 */
    private fun realFoldState(cl: ClassLoader): Boolean {
        return runCatching {
            val stubCls = cl.loadClass("com.android.server.wm.WindowManagerServiceStub")
            val inst = stubCls.method("get").invoke(null) ?: return true
            inst.javaClass.method("isDeviceStateFolded").invoke(inst) as? Boolean
        }.getOrNull() ?: true
    }

    /** 折叠↔展开切换(⑦ hook)后主动驱动一次 handleVolumeKeyRemap: 用记录的实例 + 当前
     *  handler.mWindowRotation, fold 由 ⑥ 入口再统一裁决(此处传的就是真实 fold)。 */
    private fun triggerRemap(fold: Boolean) {
        val inst = mirkInstance ?: return
        runCatching {
            val handler = inst.javaClass.field("mHandler").get(inst) ?: return
            val rotation = runCatching {
                handler.javaClass.field("mWindowRotation").get(handler) as? Int
            }.getOrNull() ?: 0
            val m = inst.javaClass.method("handleVolumeKeyRemap",
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            m.invoke(inst, fold, rotation)
            log("VolumeKeyRemapFix: ✓ 折叠切换 fold=$fold rotation=$rotation 驱动 handleVolumeKeyRemap")
        }.onFailure { log("VolumeKeyRemapFix triggerRemap failed: ${it.message}") }
    }
}
