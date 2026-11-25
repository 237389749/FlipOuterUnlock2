package com.example.flipunlock.hook.systemui

import android.provider.Settings
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.isFlip2Device
import com.example.flipunlock.hook.util.log
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 单外屏（flip2 + 内屏已拆）外屏 AOD 面板不亮 —— 2026-11-25。
 *
 * 现象：AOD 内容在合成层里是好的（`screencap -d <外屏>` / 投屏能看到点阵时钟），但**外屏物理面板全黑**，
 *   只有按电源键点亮屏幕的一瞬能看到 AOD 帧。
 *
 * 现场判据：
 *   `dumpsys display | grep mScreenState` → doze 期间是 `DOZE_SUSPEND`（有时是 `OFF`）；
 *   `dumpsys power` → `policy=DOZE ... dozeScreenState=DOZE_SUSPEND`；
 *   kernel `mi_disp` 也确实把面板设成 `Doze_Suspend`。
 *   即 AOD 只进了 framebuffer，面板没有做能看见的扫描输出。
 *
 * 对照结论（refMD `AOD_Full_Chain.md` §4）：**flip1 早就踩过这个坑** ——
 *   "flip1 实测 4 不亮、2 亮"，NoProp 的 `AodHook #3` 把 `{1,3,4}` 抬成 `2`(ON) 才点亮。
 *   unlock2 的 `AodHook` 在 flip2 上整体 SKIP（约定 flip2 的 AOD 归 NoProp），
 *   于是"flip2 + 单外屏"这条路径没人管 —— 本文件补上。
 *
 * 做法：hook 框架 `android.service.dreams.DreamService.setDozeScreenState(int)`：
 *   - `0`（FINISH/OFF：dream 真正结束）**放行** —— 抬亮会留下"屏幕亮着但 dream 已死、触摸无响应"
 *     （refMD §4.1，用户实测复现过）；
 *   - 系统设置关掉 AOD（`Settings.Secure doze_always_on != 1`）时**整体放行**，让 doze 走原生；
 *   - 其余 `{1,3,4}` → `2`（ON），AOD 内容在亮屏状态下渲染，面板必亮。
 *
 * 值：0=FINISH/OFF 1=DOZE 2=ON/PULSING 3=DOZE_SUSPEND 4=DOZE_AOD
 *
 * 开关：`persist.flipunlock.display.aod`（默认 true，与 AodHook 共用）
 * 机型 gate：flip2 **且** `persist.flipunlock.single_outer=1`（双屏 flip2 原生 state 4 能亮，不动）。
 * 与 `AodHook` 不冲突：那个文件在 flip2 入口就 SKIP，同一方法不会被两个文件同时 hook。
 */
object AodPanelLightHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui")

    /** 0=FINISH/OFF：dream 结束的正常熄屏，必须放行。 */
    private const val STATE_FINISH = 0

    /** 2=ON：面板正常扫描输出（旧版 flip1 方案）。 */
    private const val STATE_ON = 2

    /** 只为日志去重：记录上一次被抬亮的状态。 */
    private var lastForcedFrom = -1

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.displayAod) {
            log("AodPanelLight: skip, persist.flipunlock.display.aod=0")
            return
        }
        if (currentProcessName() != "com.android.systemui") {
            log("AodPanelLight: skip, process=${currentProcessName()}")
            return
        }
        if (!isFlip2Device()) {
            log("AodPanelLight: skip (非 flip2 → 走 AodHook 的 flip1 路径)")
            return
        }
        if (!Config.singleOuter) {
            log("AodPanelLight: skip (非单外屏 → 原生 state 4 可亮)")
            return
        }
        val method = runCatching {
            android.service.dreams.DreamService::class.java
                .getDeclaredMethod("setDozeScreenState", Int::class.javaPrimitiveType!!)
                .also { it.isAccessible = true }
        }.getOrNull()
        if (method == null) {
            log("AodPanelLight: DreamService.setDozeScreenState 不存在, skip")
            return
        }
        hook(method) { chain ->
            val state = chain.args.getOrNull(0) as? Int ?: return@hook chain.proceed()
            if (state == STATE_FINISH) {
                // dream 结束的正常熄屏：抬亮会变成"亮屏 + dream 已死 + 触摸无响应"
                chain.proceed()
            } else if (!aodEnabledBySetting()) {
                // 系统设置关了 AOD → 不抬屏（与 refMD §4.1 的 gate 一致）
                if (lastForcedFrom != -100) {
                    lastForcedFrom = -100
                    log("AodPanelLight: doze_always_on != 1 → setDozeScreenState($state) 放行")
                }
                chain.proceed()
            } else {
                if (lastForcedFrom != state) {
                    lastForcedFrom = state
                    log("AodPanelLight: setDozeScreenState($state) → $STATE_ON (单外屏抬亮)")
                }
                chain.proceed(arrayOf<Any?>(STATE_ON))
            }
        }
        log("AodPanelLight: ✓ DreamService.setDozeScreenState hooked (flip2/single-outer → ON)")
    }

    /** AOD 开关：系统设置 `doze_always_on==1` 才算开；取不到 Context 时保守按开处理。 */
    private fun aodEnabledBySetting(): Boolean = runCatching {
        val ctx = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return@runCatching true
        Settings.Secure.getInt(ctx.contentResolver, "doze_always_on", 0) == 1
    }.getOrDefault(true)
}
