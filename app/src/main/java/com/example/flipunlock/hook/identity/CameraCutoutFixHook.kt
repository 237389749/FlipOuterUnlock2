package com.example.flipunlock.hook.identity

import android.graphics.Rect
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 相机 cutout 修复（2026-08-12，flip1 + 属性 1 实测）。
 *
 * 现象：属性 1（multi_display_type=1）下外屏 cutout 的运行时绑定（@bind_right_cutout）
 * 未生效 → boundingRects 全空 → 相机 `CamLayoutManagerImpl.<init>` 读 `Rect.right` NPE 闪退
 * （`l3.t.<init>` → `F0.i.j` → `CamLayoutManagerImpl`）。
 *
 * 根因（参考 FlipOuterUnlock2 CutoutRemove 注释）：
 *   相机代码 `Optional.ofNullable(display.getCutout()).flatMap(...).ifPresent(...)`
 *   属性 1 下 `Display.getCutout()` 返回 null → Optional 链短路 → 字段未设置 → NPE。
 *   不是"bounds 全空"的问题（§25 零 bounds 警示是另一条清零路径），核心是 **getCutout() 不能返回 null**。
 *
 * 修复（照搬 FU2 CutoutRemove #2/#3）：相机进程内
 *   #1 Display.getCutout() → 返回有效 DisplayCutout（零 insets + 零 bounds，5 参构造）
 *   #2 DisplayCutout.getBoundingRect* → 空 Rect（防御）
 *
 * 只作用于 com.android.camera，不影响其他进程的 cutout 语义。
 */
object CameraCutoutFixHook : BaseHook() {
    override val targetPackages = listOf("com.android.camera")

    override fun setupHooks(param: PackageReadyParam) {
        log("CameraCutoutFixHook: loading for ${param.packageName}")
        safeHook("CameraCutoutFixHook") {
            hookDisplayGetCutout(param.classLoader)
            hookBoundingRects(param.classLoader)
        }
    }

    // ── #1 Display.getCutout() → valid non-null DisplayCutout ──
    private fun hookDisplayGetCutout(classLoader: ClassLoader) {
        runCatching {
            val displayClass = classLoader.loadClass("android.view.Display")
            val dcClass = classLoader.loadClass("android.view.DisplayCutout")
            val insetsClass = classLoader.loadClass("android.graphics.Insets")
            val intClass = Int::class.javaPrimitiveType!!
            val zeroInsets = insetsClass.method("of", intClass, intClass, intClass, intClass)
                .invoke(null, 0, 0, 0, 0)
            val zeroRect = Rect(0, 0, 0, 0)
            val safeCutout = dcClass.getConstructor(
                insetsClass, Rect::class.java, Rect::class.java, Rect::class.java, Rect::class.java
            ).newInstance(zeroInsets, zeroRect, zeroRect, zeroRect, zeroRect)
            hook(displayClass.method("getCutout"), replaceResult(safeCutout))
            log("CameraCutoutFixHook: ✓ Display.getCutout → valid DisplayCutout (non-null)")
        }.onFailure { log("CameraCutoutFixHook: #1 Display.getCutout failed", it) }
    }

    // ── #2 DisplayCutout getters → empty Rect (defense) ──
    private fun hookBoundingRects(classLoader: ClassLoader) {
        val dcClass = classLoader.loadClass("android.view.DisplayCutout")
        val emptyRect = Rect(0, 0, 0, 0)
        for (name in listOf("getBoundingRectLeft", "getBoundingRectRight",
                "getBoundingRectTop", "getBoundingRectBottom")) {
            runCatching {
                hook(dcClass.getMethod(name), replaceResult(emptyRect))
            }.onFailure { /* 忽略（final/不存在）*/ }
        }
        runCatching {
            hook(dcClass.getMethod("getBoundingRects"), replaceResult(emptyList<Rect>()))
        }.onFailure { /* 忽略 */ }
        log("CameraCutoutFixHook: ✓ DisplayCutout getBoundingRect* → empty (defense)")
    }
}
