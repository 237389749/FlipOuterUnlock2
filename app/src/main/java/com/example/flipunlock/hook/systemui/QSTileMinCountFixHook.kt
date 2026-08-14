package com.example.flipunlock.hook.systemui

import android.content.res.Resources
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 解除控制中心编辑模式"最少磁贴数"限制（2026-08-14 重写, 双保险）。
 *
 * 逻辑链(refMD §43.6/43.6.1 + b5c1/flip2-systemui 反编译):
 *   MiuiTileAdapter 构造(b5c1:295 / flip2:295):
 *     mMinNumTiles = context.getResources().getInteger(R.integer.quick_settings_min_num_tiles)  // 默认 12
 *   删除磁贴(flip2:385): if (pos < mEditIndex && mCurrentSpecs.size() > mMinNumTiles) → 允许移除
 *   拖拽(flip2:165):     if (mCurrentSpecs.size() > mMinNumTiles) → 允许拖动
 *   → mMinNumTiles 是"最少可编辑磁贴数"闸门(4 固定 + 8 可编辑 = 12)。
 *
 * 修复(双保险, Lite 单 hook Resources.getInteger 可能因时机/装设未生效):
 *   保险 1: Resources.getInteger(int) → quick_settings_min_num_tiles 返回 0
 *   保险 2: MiuiTileAdapter 构造后反射把 mMinNumTiles 字段置 0
 *           (public final int, 反射 setAccessible + setInt; 绕开资源读取, 100% 生效)
 *
 * 进程: com.android.systemui (可能以 pkg=android 回调 → targetPackages 含 "android" + 进程限定)
 */
object QSTileMinCountFixHook : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "android")

    override fun setupHooks(param: PackageReadyParam) {
        val process = currentProcessName()
        if (process != "com.android.systemui") {
            log("QSTileMinCountFix: skip, process=$process")
            return
        }
        log("QSTileMinCountFix: loading for ${param.packageName} (process=$process)")
        safeHook("QSTileMinCountFix") {
            // ── 保险 1: Resources.getInteger → 0 ──
            runCatching {
                val resClass = param.classLoader.loadClass("android.content.res.Resources")
                val method = resClass.method("getInteger", Int::class.javaPrimitiveType!!)
                hook(method) { chain ->
                    val res = chain.thisObject as? Resources ?: return@hook chain.proceed()
                    val id = chain.args[0] as? Int ?: return@hook chain.proceed()
                    val name = runCatching { res.getResourceName(id) }.getOrNull()
                    if (name != null && name.endsWith("quick_settings_min_num_tiles")) {
                        log("QSTileMinCountFix: 保险1 $name → 0")
                        return@hook 0
                    }
                    chain.proceed()
                }
                log("QSTileMinCountFix: ✓ 保险1 Resources.getInteger hooked")
            }.onFailure { log("QSTileMinCountFix: 保险1 failed: ${it.message}") }

            // ── 保险 2: MiuiTileAdapter 构造后反射改 mMinNumTiles=0 ──
            runCatching {
                val cls = param.classLoader.findClassUp(
                    "com.android.systemui.qs.customize.MiuiTileAdapter")
                if (cls == null) {
                    log("QSTileMinCountFix: MiuiTileAdapter not found (R8 drift?)")
                    return@safeHook
                }
                val field = cls.field("mMinNumTiles")
                val ctor = cls.declaredConstructors.firstOrNull()
                if (ctor == null) {
                    log("QSTileMinCountFix: no constructor")
                    return@safeHook
                }
                hook(ctor, after { chain, _ ->
                    runCatching {
                        field.setInt(chain.thisObject, 0)
                    }.onFailure { log("QSTileMinCountFix: 保险2 field set failed: ${it.message}") }
                })
                log("QSTileMinCountFix: ✓ 保险2 MiuiTileAdapter.<init> → mMinNumTiles=0")
            }.onFailure { log("QSTileMinCountFix: 保险2 failed: ${it.message}") }
        }
    }
}
