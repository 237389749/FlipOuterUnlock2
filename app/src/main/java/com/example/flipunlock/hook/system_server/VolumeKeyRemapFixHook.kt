package com.example.flipunlock.hook.system_server

import android.content.Context
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 恢复 flip 折叠态「音量键方向跟随旋转」（v3，2026-09-30 国际版 ruyi_global 实机定位后重写）。
 *
 * ── 现象 ──
 * 折叠(外屏)时音量键方向不跟随屏幕: 外屏 display0 `installOrientation=ROTATION_180`
 * (物理上下与屏幕相反), 原生会在「折叠 + 竖屏(rotation==0)」时互换 VOLUME_UP(24)/VOLUME_DOWN(25),
 * 横屏/展开时恢复物理方向。属性层(persist.sys.multi_display_type=1)后该功能整体失效。
 *
 * ── 真根因: 该功能被三处 isFlipDevice 门依次挡死（国际版 miui-services 反编译 + 实机探针）──
 * 1. `MiInputKeyRemap.supportVolumeKeyRemap()`(:131-133) = `MiuiMultiDisplayTypeInfo.isFlipDevice()`
 *    → 属性1 → false。实测活探针: `dumpsys window` → `MiInputKeyRemap IsSupportVolumeKeyRemap=false`
 *    （`dump()`:227 是**实时调用**该方法, 不是缓存字段 → 证明该门确实是 false）
 *    ⚠️ refMD §44.6.2 记的「flip1 硬编码设备代号恒 true, 无需本 hook」**在国际版不成立**
 *    —— 国际版与 flip2 同款实现, flip1 同样需要 hook。
 * 2. `BaseMiuiPhoneWindowManager` `systemReadyInternal()`(:795) 里:
 *      `if (IS_FOLD_DEVICE || IS_FLIP_DEVICE) registerDisplayFoldListener(mIDDisplayFoldListener);`
 *    `IS_FOLD_DEVICE/IS_FLIP_DEVICE` 是 **static final**(:313 类加载期读 isFlipDevice) → 双双 false
 *    → **fold 监听从未注册** → `$3.onDisplayFoldChanged()` 永不回调
 *    → 其中的 `MiInputKeyRemap.notifyFoldStatus(folded)`(:342-343) 永不执行 → 永不 remap。
 *    实机侧证: `dumpsys window` 里 policy `mIsFolded=false`, 而设备实际是 FOLDED
 *    （DeviceState=CLOSED / `FoldController mDeviceState=FOLDED`）→ 折叠状态从未同步进 policy。
 *    ⇒ **这是整条链真正缺失的一步**（`mMiInputKeyRemap` 在 :802 是无条件初始化的）。
 * 3. `WindowManagerServiceImpl.isDeviceStateFolded()`(:3233) = `mFoldedDeviceStates` 含当前 state,
 *    而该数组取 `R.array.config_halfFoldedDeviceStates`(:587) —— 该 framework 资源在**本机是空数组**
 *    (`aapt2 dump resources framework-res.apk` → size=0) → 恒 false。
 *    ⇒ v2 的 ⑥ 层用它当 fold 源, 会把正确的 fold=true 覆盖成 false → **永不 remap**。
 *    （v3 起 ⑥ 改为以本 hook 观测到的真实 fold 为准）
 *
 * ── v3 修复（上游优先）──
 * ① `supportVolumeKeyRemap() → true`（总门; 同时让构造期 registerRotationWatcher 生效）
 * ⑨ **核心**: hook `BaseMiuiPhoneWindowManager.systemReadyInternal()` after → 原生门为假时反射调用
 *    `registerDisplayFoldListener(mIDDisplayFoldListener)` 补上缺失的一步
 *    → `DisplayFoldController.registerDisplayFoldListener`(:111-129) 会**立即回调**一次真实 fold
 *    → `onDisplayFoldChanged` → `mFolded` 同步 + `notifyFoldStatus` → handler case1 → remap ✓
 *    （顺带修复 policy `mFolded` 恒 false 的近邻问题: 背敲手势/近距离传感器/指纹的
 *      notifyFoldStatus 都在同一回调里）
 * ⑦⑧ 折叠切换信号（setDeviceFolded / FoldScreenListenerStubImpl）→ 主动驱动一次
 * ⑥ 裁决层: fold 以本 hook 观测到的真实状态为准（不再用国际版恒 false 的 isDeviceStateFolded）
 * ③④②⑤ 保留为状态同步/兜底; ⑤②不再硬编码 fold=true（展开态开机会错误 remap）
 * ⑩ 旋转兜底: `DisplayRotation.updateRotationUnchecked` after 读 `mRotation`(displayId==0),
 *    变化时用当前 fold 驱动一次（补上 MiInputKeyRemap 自带 RotationWatcher 未注册的场景）
 *
 * ── 验证方法（本机 system_server 模块日志读不到, 用 MIUI 自己的输入日志）──
 * `adb shell logcat -d -s MiuiInputKeyEventLog`（`MiuiInputLog` 用 Slog.w/i, 不受调试等级影响）:
 *   `display changed,display=0 fold=true mIsFoldChanged=true` ← 原生 fold 回调触发(⑨ 生效)
 *   `formKeyCode = 24 toKeyCode = 25` + `25 toKeyCode = 24`  ← remapVolumeKey() = 功能打开 ✓
 *   `formKeyCode = 24 toKeyCode = 24` + `25 toKeyCode = 25`  ← restoreVolumeKey() = 横屏恢复 ✓
 * 2026-09-30 实机结果: 折叠竖屏 remap ✓ / 折叠横屏(1392x1208 ROTATION_270) restore ✓。
 *
 * 进程: system_server（LSPosed scope 已含 "system"）。
 * 开关: persist.flipunlock.volume.keyremap（默认 true）
 */
object VolumeKeyRemapFixHook {

    private const val CLS_MIRK = "com.android.server.input.MiInputKeyRemap"
    private const val CLS_POLICY = "com.android.server.policy.BaseMiuiPhoneWindowManager"
    private const val CLS_FOLD_CTRL = "com.android.server.policy.DisplayFoldController"
    private const val CLS_FOLD_STUB = "com.android.server.wm.FoldScreenListenerStubImpl"
    private const val CLS_IDISPLAY_FOLD = "android.view.IDisplayFoldListener"
    private const val CLS_DISPLAY_ROTATION = "com.android.server.wm.DisplayRotation"

    /** 最近一次 MiInputKeyRemap 实例（供主动驱动 handleVolumeKeyRemap）。 */
    @Volatile private var mirkInstance: Any? = null

    /** BaseMiuiPhoneWindowManager 实例（读 mFolded / mContext）。 */
    @Volatile private var policyInstance: Any? = null

    /** 本 hook 观测到的真实折叠状态（来自 ⑨ 注册回调 / ⑦ / ⑧ / policy.mFolded）。 */
    @Volatile private var foldState: Boolean? = null

    /** 最近一次已知旋转（0/1/2/3; 1、3 = 横屏）。 */
    @Volatile private var rotationState: Int = -1

    fun hook(param: SystemServerStartingParam) {
        if (!Config.volumeKeyRemap) return
        log("VolumeKeyRemapFix: setting up (v3)")

        // ── ① 功能总门: supportVolumeKeyRemap() → true ──
        runCatching {
            val cls = loadClass(param, CLS_MIRK) ?: error("$CLS_MIRK 不可加载")
            hook(cls.method("supportVolumeKeyRemap"), replaceResult(true))
            log("VolumeKeyRemapFix: ✓ ① supportVolumeKeyRemap → true")
        }.onFailure { log("VolumeKeyRemapFix ① failed: ${it.message}") }

        // ── ⑨ 核心: 补注册 fold 监听（原生被 IS_FLIP_DEVICE 静态门挡死）──
        runCatching {
            val cls = loadClass(param, CLS_POLICY) ?: error("$CLS_POLICY 不可加载")
            hook(cls.method("systemReadyInternal"), after { chain, result ->
                val policy = chain.thisObject
                policyInstance = policy
                foldState = policyFold() ?: foldState
                val nativeRegistered = runCatching {
                    (cls.field("IS_FOLD_DEVICE").get(null) as? Boolean == true) ||
                        (cls.field("IS_FLIP_DEVICE").get(null) as? Boolean == true)
                }.getOrDefault(false)
                if (!nativeRegistered) {
                    runCatching {
                        val listener = policy?.getField("mIDisplayFoldListener")
                            ?: error("mIDisplayFoldListener 取不到")
                        val reg = policy.javaClass.method(
                            "registerDisplayFoldListener", Class.forName(CLS_IDISPLAY_FOLD))
                        reg.invoke(policy, listener)
                        log("VolumeKeyRemapFix: ✓ ⑨ 补注册 fold 监听(原生静态门挡死) → 原生链恢复")
                    }.onFailure { log("VolumeKeyRemapFix ⑨ 注册失败: ${it.message}") }
                } else {
                    log("VolumeKeyRemapFix: ⑨ 原生已注册 fold 监听, 跳过")
                }
                result
            })
        }.onFailure { log("VolumeKeyRemapFix ⑨ failed: ${it.message}") }

        // ── ⑦ DisplayFoldController.setDeviceFolded(boolean) after → 折叠切换驱动 ──
        runCatching {
            val cls = loadClass(param, CLS_FOLD_CTRL) ?: error("$CLS_FOLD_CTRL 不可加载")
            hook(cls.method("setDeviceFolded", Boolean::class.javaPrimitiveType!!), after { chain, result ->
                val fold = chain.args[0] as? Boolean ?: return@after result
                drive(fold, currentRotation())
                result
            })
            log("VolumeKeyRemapFix: ✓ ⑦ DisplayFoldController.setDeviceFolded after")
        }.onFailure { log("VolumeKeyRemapFix ⑦ failed: ${it.message}") }

        // ── ⑧ FoldScreenListenerStubImpl.onDeviceFoldStateChanged(boolean) after（MIUI stub 双保险）──
        runCatching {
            val cls = loadClass(param, CLS_FOLD_STUB) ?: error("$CLS_FOLD_STUB 不可加载")
            hook(cls.method("onDeviceFoldStateChanged", Boolean::class.javaPrimitiveType!!), after { chain, result ->
                val fold = chain.args[0] as? Boolean ?: return@after result
                drive(fold, currentRotation())
                result
            })
            log("VolumeKeyRemapFix: ✓ ⑧ FoldScreenListenerStubImpl.onDeviceFoldStateChanged after")
        }.onFailure { log("VolumeKeyRemapFix ⑧ failed: ${it.message}") }

        // ── ⑥ 裁决层: fold 以本 hook 观测到的真实状态为准 ──
        runCatching {
            val cls = loadClass(param, CLS_MIRK) ?: error("$CLS_MIRK 不可加载")
            hook(cls.method("handleVolumeKeyRemap",
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)) { chain ->
                val argFold = chain.args[0] as? Boolean ?: false
                val rotation = chain.args[1] as? Int ?: 0
                val fold = foldState ?: argFold
                if (fold != argFold) {
                    log("VolumeKeyRemapFix: ⑥ fold $argFold → $fold (rotation=$rotation)")
                }
                chain.proceed(arrayOf<Any?>(fold, rotation))
            }
            log("VolumeKeyRemapFix: ✓ ⑥ handleVolumeKeyRemap 裁决(fold=真实折叠状态)")
        }.onFailure { log("VolumeKeyRemapFix ⑥ failed: ${it.message}") }

        // ── ③ notifyFoldStatus(boolean) after → 记录 + 立即驱动 ──
        runCatching {
            val cls = loadClass(param, CLS_MIRK) ?: error("$CLS_MIRK 不可加载")
            hook(cls.method("notifyFoldStatus", Boolean::class.javaPrimitiveType!!), after { chain, result ->
                val fold = chain.args[0] as? Boolean ?: return@after result
                drive(fold, currentRotation())
                result
            })
            log("VolumeKeyRemapFix: ✓ ③ notifyFoldStatus after")
        }.onFailure { log("VolumeKeyRemapFix ③ failed: ${it.message}") }

        // ── ④ notifyWindowRotation(int) after → 记录旋转 + 立即驱动 ──
        runCatching {
            val cls = loadClass(param, CLS_MIRK) ?: error("$CLS_MIRK 不可加载")
            hook(cls.method("notifyWindowRotation", Int::class.javaPrimitiveType!!), after { chain, result ->
                val rotation = chain.args[0] as? Int ?: return@after result
                drive(foldState ?: policyFold() ?: return@after result, rotation)
                result
            })
            log("VolumeKeyRemapFix: ✓ ④ notifyWindowRotation after")
        }.onFailure { log("VolumeKeyRemapFix ④ failed: ${it.message}") }

        // ── ② getInstance(Context) after → 捕获实例（不再硬编码 fold=true）──
        runCatching {
            val cls = loadClass(param, CLS_MIRK) ?: error("$CLS_MIRK 不可加载")
            hook(cls.method("getInstance", Context::class.java), after { chain, result ->
                if (result != null) {
                    mirkInstance = result
                    foldState = foldState ?: policyFold()
                }
                result
            })
            log("VolumeKeyRemapFix: ✓ ② getInstance after")
        }.onFailure { log("VolumeKeyRemapFix ② failed: ${it.message}") }

        // ── ⑤ 私有构造 after → 捕获实例（双保险, 不依赖 getInstance 返回值）──
        runCatching {
            val cls = loadClass(param, CLS_MIRK) ?: error("$CLS_MIRK 不可加载")
            val ctor = cls.getDeclaredConstructor(Context::class.java).also { it.isAccessible = true }
            hook(ctor, after { chain, result ->
                chain.thisObject?.let {
                    mirkInstance = it
                    foldState = foldState ?: policyFold()
                }
                result
            })
            log("VolumeKeyRemapFix: ✓ ⑤ 构造 after")
        }.onFailure { log("VolumeKeyRemapFix ⑤ failed: ${it.message}") }

        // ── ⑩ 旋转兜底: DisplayRotation.updateRotationUnchecked after（displayId==0）──
        runCatching {
            val cls = loadClass(param, CLS_DISPLAY_ROTATION) ?: error("$CLS_DISPLAY_ROTATION 不可加载")
            hook(cls.method("updateRotationUnchecked", Boolean::class.javaPrimitiveType!!), after { chain, result ->
                val inst = chain.thisObject ?: return@after result
                val displayId = runCatching {
                    inst.getField("mDisplayContent")?.callMethod("getDisplayId") as? Int
                }.getOrNull() ?: return@after result
                if (displayId != 0) return@after result
                val rotation = runCatching { inst.getField("mRotation") as? Int }.getOrNull()
                    ?: return@after result
                if (rotation == rotationState) return@after result
                val fold = foldState ?: policyFold() ?: return@after result
                drive(fold, rotation)
                result
            })
            log("VolumeKeyRemapFix: ✓ ⑩ DisplayRotation.updateRotationUnchecked after(rotation 兜底)")
        }.onFailure { log("VolumeKeyRemapFix ⑩ failed: ${it.message}") }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** 多 classloader 解析: system_server 里 MIUI jar 未必在 param.classLoader 可见路径上。 */
    private fun loadClass(param: SystemServerStartingParam, name: String): Class<*>? {
        val loaders = listOfNotNull(
            param.classLoader,
            runCatching { Thread.currentThread().contextClassLoader }.getOrNull(),
            runCatching { ClassLoader.getSystemClassLoader() }.getOrNull(),
        )
        for (loader in loaders) {
            runCatching { loader.loadClass(name) }.getOrNull()?.let { return it }
        }
        return runCatching { Class.forName(name) }.getOrNull()
    }

    /** policy.mFolded（⑨ 修复后由 fold 回调驱动, 是可信源）。 */
    private fun policyFold(): Boolean? =
        runCatching { policyInstance?.getField("mFolded") as? Boolean }.getOrNull()

    /** 当前显示旋转: 优先读 policy.mContext 的默认显示（= 外屏 display0, 与原生语义一致）。 */
    private fun currentRotation(): Int {
        val ctx = runCatching { policyInstance?.getField("mContext") as? Context }.getOrNull()
        val rot = runCatching { ctx?.display?.rotation }.getOrNull()
        return rot ?: rotationState
    }

    /** 记录状态并驱动 MiInputKeyRemap.handleVolumeKeyRemap(fold, rotation)。 */
    private fun drive(fold: Boolean, rotation: Int) {
        foldState = fold
        if (rotation >= 0) rotationState = rotation
        val inst = mirkInstance ?: return
        runCatching {
            val m = inst.javaClass.method("handleVolumeKeyRemap",
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            m.invoke(inst, fold, rotation)
        }.onFailure { log("VolumeKeyRemapFix drive failed: ${it.message}") }
    }
}
