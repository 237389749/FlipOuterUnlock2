package com.example.flipunlock.hook.identity

import android.graphics.Insets
import android.graphics.Path
import android.graphics.Rect
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * App 端 cutout 全屏四件套（unlock2 CutoutRemove 的 app 侧移植，§34.6 候选3）——
 * 无需 system_server 注入，app 进程注入正常即生效（flip2/flip1 通用）。
 *
 * flip2 DISPLAY_CUTOUT letterbox 链（客户端源头）：
 *   app 进程 WindowLayout.computeFrames 用 display.getCutout() 计算父窗口裁剪 →
 *   isParentFrameClippedByDisplayCutout=true → services 侧
 *   isLetterboxedForDisplayCutout 条件①成立 → letterbox。
 *
 * #1 CutoutSpecification.Parser.parse(String) → after 清零（mInsets/mPath/bounds）
 *    —— 设备 cutout 规格源头清零（unlock2 核心方案）
 * #2 android.view.Display.getCutout() → 空 DisplayCutout（zero insets + zero bounds）
 *    —— app 进程拿到的 cutout 全空 → 父窗口不被裁剪 → ① 不成立（不能返回 null，
 *       camera 类代码 Optional 链会 NPE，unlock2 实测）
 * #3 DisplayCutout.getBoundingRect* → 空 Rect（防御）
 * #4 WindowLayoutStubImpl.getLayoutInDisplayCutoutMode → 3(ALWAYS)
 *    —— WindowLayout.computeFrames L94 跳过裁剪（双保险）
 *
 * 影响：所有作用域 app 的窗口不避让挖孔（全局全屏），与 flip1 CutoutRemove 语义一致。
 */
object CutoutAlwaysHook : BaseHook() {
    override val targetPackages = listOf("*")

    override fun setupHooks(param: PackageReadyParam) {
        // camera 排除（2026-08-10）：camera 由 CameraReverseHook 恢复 flip 身份（属性读 4），
        // 需要真实 cutout 做 flip 布局；本 hook 清零 cutout 会破坏 camera 布局。
        if (param.packageName == "com.android.camera") {
            log("CutoutAlwaysHook: skip camera (real cutout preserved for flip layout)")
            return
        }
        log("CutoutAlwaysHook: loading for ${param.packageName}")
        safeHook("CutoutAlwaysHook") {
            hookParserParse(param.classLoader)
            hookDisplayGetCutout(param.classLoader)
            hookBoundingRects(param.classLoader)
            forceCutoutModeAlways(param.classLoader)
        }
    }

    // ── #1 CutoutSpecification.Parser.parse → zero ALL fields ──
    private fun hookParserParse(classLoader: ClassLoader) {
        runCatching {
            val parserClass = classLoader.loadClass("android.view.CutoutSpecification\$Parser")
            val parseMethod = parserClass.method("parse", String::class.java)
            hook(parseMethod, after { chain, result ->
                val spec = result ?: return@after result
                spec.setField("mInsets", Insets.of(0, 0, 0, 0))
                spec.setField("mPath", Path())
                spec.setField("mLeftBound", Rect(0, 0, 0, 0))
                spec.setField("mRightBound", Rect(0, 0, 0, 0))
                spec.setField("mTopBound", Rect(0, 0, 0, 0))
                spec.setField("mBottomBound", Rect(0, 0, 0, 0))
                log("CutoutAlwaysHook: ✓ Parser.parse → zeroed ALL fields")
                result
            })
        }.onFailure { log("CutoutAlwaysHook: #1 Parser.parse failed: ${it.message}") }
    }

    // ── #2 Display.getCutout() → valid empty DisplayCutout ──
    private fun hookDisplayGetCutout(classLoader: ClassLoader) {
        runCatching {
            val displayClass = classLoader.loadClass("android.view.Display")
            val dcClass = classLoader.loadClass("android.view.DisplayCutout")
            val insetsClass = classLoader.loadClass("android.graphics.Insets")
            val zeroInsets = insetsClass.method("of", Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
                .invoke(null, 0, 0, 0, 0)
            val zeroRect = Rect(0, 0, 0, 0)
            val safeCutout = dcClass.getConstructor(
                insetsClass, Rect::class.java, Rect::class.java, Rect::class.java, Rect::class.java
            ).newInstance(zeroInsets, zeroRect, zeroRect, zeroRect, zeroRect)
            hook(displayClass.method("getCutout"), replaceResult(safeCutout))
            log("CutoutAlwaysHook: ✓ Display.getCutout → empty DisplayCutout")
        }.onFailure { log("CutoutAlwaysHook: #2 Display.getCutout failed: ${it.message}") }
    }

    // ── #3 DisplayCutout getters → empty Rect (defense) ──
    private fun hookBoundingRects(classLoader: ClassLoader) {
        val dcClass = classLoader.loadClass("android.view.DisplayCutout")
        val emptyRect = Rect(0, 0, 0, 0)
        for (name in listOf("getBoundingRectLeft", "getBoundingRectRight",
                "getBoundingRectTop", "getBoundingRectBottom")) {
            runCatching {
                hook(dcClass.getMethod(name), replaceResult(emptyRect))
                log("CutoutAlwaysHook: ✓ DisplayCutout.$name → empty")
            }.onFailure { /* 忽略（final/不存在）*/ }
        }
        runCatching {
            hook(dcClass.getMethod("getBoundingRects"), replaceResult(emptyList<Rect>()))
        }.onFailure { /* 忽略 */ }
    }

    // ── #4 getLayoutInDisplayCutoutMode → ALWAYS (3) ──
    private fun forceCutoutModeAlways(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("android.view.WindowLayoutStubImpl")
            hook(cls.method("getLayoutInDisplayCutoutMode",
                android.view.WindowManager.LayoutParams::class.java), replaceResult(3))
            log("CutoutAlwaysHook: ✓ getLayoutInDisplayCutoutMode → 3 (ALWAYS)")
        }.onFailure { log("CutoutAlwaysHook: #4 getLayoutInDisplayCutoutMode failed: ${it.message}") }
    }
}
