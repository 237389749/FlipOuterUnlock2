package com.example.flipunlock.hook.blur

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicLong

/**
 * 模糊下发探针 + 可选去重（2026-11-17，配合 refMD §44.13「控制中心闪烁」）。
 *
 * ── 背景 ─────────────────────────────────────────────────────────────────────
 * bixi/OS4.0.3 上控制中心、大文件夹的背景会整屏"呼吸"：SF 每帧重建壁纸层的 blur shader
 * （`makePassBlurBetterDownShader layername=Wallpaper BBQ wrapper#72`，实测 ≈490/s），
 * 两次结果色调不同 ⇒ 整屏 89~95% 像素在两种状态间切。
 * `background_blur_enable=0` + 重启 SystemUI 能把频率从"每帧"降到"每 5~6 秒一次"，
 * 但没根除 ⇒ 需要知道**到底是哪个 Java API 在持续下发 blur**。
 *
 * SF 侧（`dumpsys SurfaceFlinger`）看到的是：
 *   `MiOutput PassBlur size:3` / `RenderEngine chosen blur algorithm: KawaseDualFilterV2`
 *   `restore_blur_step: true` / `small_blur_region_improvements: false`
 *   `Layer [..] SoScBlurController#..`（pid=SystemUI）
 * ⇒ 重算发生在 SF 原生层，Java 侧只能改**输入**（半径/开关/区域）。
 * 所以本文件先做探针，量清楚输入侧是谁在动：
 *
 *   ① `android.view.View#setMiSelfBlur(int, java.util.List)`   ← MIUI 视图自模糊
 *      SystemUI 里由 `ShadeWindowBlurControllerImpl.applyMiSelfBlurEffect()` 调用：
 *        int r = (int) blurUtils.blurRadiusOfRatio(f); view.setMiSelfBlur(r, colors)
 *      若 `f`（blurRatio 动画流）每帧微抖 ⇒ 半径在两个整数间跳 ⇒ SF 每帧重建。
 *   ② `android.view.SurfaceControl$Transaction#setBackgroundBlurRadius(SurfaceControl, int)`
 *      窗口级背景模糊。
 *
 * ── 两个开关 ─────────────────────────────────────────────────────────────────
 *   `persist.flipunlock.blur.probe`  （默认 true）只统计+限流打印，**不改行为**
 *   `persist.flipunlock.blur.stab`   （默认 false）**去重**：同一个 View 的半径没变就不重复下发
 *      —— 半径是整数，若 f 打磨但仍落在同一整数，重复下发纯属浪费且会触发 SF 重建。
 *      打开后若闪烁消失，即证明"每帧重复下发同一半径"是可见闪烁的直接成因。
 *
 * 进程: com.android.systemui + com.miui.home（两个进程都要看，SF 日志里两条 VRI 都在刷）
 * 注: 纯运行时，无持久化副作用。
 */
object BlurProbe : BaseHook() {

    override val targetPackages = listOf("com.android.systemui", "com.miui.home")

    private const val CLS_VIEW = "android.view.View"
    private const val CLS_TXN = "android.view.SurfaceControl\$Transaction"

    /** 限流：前 HEAD_LOG 次逐条打印（用于判断"到底有没有被调用"），之后每 LOG_EVERY 次一条 */
    private const val HEAD_LOG = 60L
    private const val LOG_EVERY = 300L

    private val miSelfBlurCalls = AtomicLong(0)
    private val miSelfBlurSkips = AtomicLong(0)
    private val txnBlurCalls = AtomicLong(0)

    /** 每个 View 上次下发的半径（去重用，弱引用避免泄漏不是重点：View 数量有限） */
    private val lastRadius = java.util.WeakHashMap<Any, Int>()
    private val distinctRadii = java.util.Collections.synchronizedSet(LinkedHashSet<Int>())

    override fun setupHooks(param: PackageReadyParam) {
        val probe = Config.blurProbe
        val stab = Config.blurStab
        if (!probe && !stab) {
            log("BlurProbe: 两个开关都关 (blur.probe/blur.stab)，skip")
            return
        }
        log("BlurProbe: loading for ${param.packageName} proc=${currentProcessName()} probe=$probe stab=$stab")
        val cl = processClassLoader(param.classLoader)

        hookMiSelfBlur(cl, stab)
        hookTxnRadius(cl)
    }

    /**
     * `View#setMiSelfBlur(...)` —— 4.0.3 上**签名与反编译的 `(int, List)` 不符**
     * （实测 `android.view.View#setMiSelfBlur` NoSuchMethod），所以这里：
     *   1. 先把 View 上所有名字含 "Blur" 的方法签名打出来（一次性，定位真实 API）
     *   2. 再按名字前缀 `setMiSelfBlur` 把所有重载都挂上（不猜参数类型）
     */
    private fun hookMiSelfBlur(cl: ClassLoader, stab: Boolean) = safeHook("BlurProbe.setMiSelfBlur") {
        val viewCls = cl.findClassUp(CLS_VIEW) ?: run {
            log("BlurProbe: $CLS_VIEW 不可加载")
            return@safeHook
        }

        // 一次性把候选签名打出来，别再靠猜
        val candidates = runCatching {
            viewCls.declaredMethods.filter { it.name.contains("Blur", ignoreCase = true) }
        }.getOrDefault(emptyList())
        if (candidates.isEmpty()) {
            log("BlurProbe: View 上没有任何名字含 Blur 的方法（可能都在 MiBlurCompat 里）")
        } else {
            candidates.take(20).forEach { m ->
                log("BlurProbe: View#${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
        }

        val targets = candidates.filter { it.name.startsWith("setMiSelfBlur") }
        if (targets.isEmpty()) {
            log("BlurProbe: 没有 setMiSelfBlur* 可挂，只保留 Transaction 探针")
            return@safeHook
        }

        targets.forEach { m ->
            runCatching {
                hook(m) { chain ->
                    val radius = (chain.args.getOrNull(0) as? Int) ?: -1
                    val thisObj = chain.thisObject
                    val n = miSelfBlurCalls.incrementAndGet()
                    distinctRadii.add(radius)

                    if (stab && thisObj != null) {
                        val prev = synchronized(lastRadius) { lastRadius[thisObj] }
                        if (prev != null && prev == radius) {
                            miSelfBlurSkips.incrementAndGet()
                            if (n <= HEAD_LOG || n % LOG_EVERY == 0L) {
                                log(
                                    "BlurProbe: calls=$n skips=${miSelfBlurSkips.get()} " +
                                        "radii=${distinctRadii.toList().take(12)} (stab=ON)"
                                )
                            }
                            return@hook null
                        }
                        synchronized(lastRadius) { lastRadius[thisObj] = radius }
                    }

                    if (n <= HEAD_LOG || n % LOG_EVERY == 0L) {
                        log(
                            "BlurProbe: calls=$n radius=$radius args=${chain.args.size} " +
                                "radii=${distinctRadii.toList().take(12)} stab=$stab"
                        )
                    }
                    chain.proceed()
                }
                log("BlurProbe: ✓ View#${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) hooked")
            }.onFailure { log("BlurProbe: hook View#${m.name} failed: ${it.message}") }
        }
    }

    /** `SurfaceControl.Transaction#setBackgroundBlurRadius(SurfaceControl, int)` */
    private fun hookTxnRadius(cl: ClassLoader) = safeHook("BlurProbe.txnRadius") {
        val txn = cl.findClassUp(CLS_TXN) ?: run {
            log("BlurProbe: $CLS_TXN 不可加载")
            return@safeHook
        }
        val scCls = cl.findClassUp("android.view.SurfaceControl") ?: return@safeHook
        val m = runCatching {
            txn.method("setBackgroundBlurRadius", scCls, Int::class.javaPrimitiveType!!)
        }.getOrNull() ?: run {
            log("BlurProbe: setBackgroundBlurRadius(SurfaceControl,int) 找不到")
            return@safeHook
        }
        hook(m) { chain ->
            val r = chain.args.getOrNull(1) as? Int ?: -1
            val n = txnBlurCalls.incrementAndGet()
            if (n <= HEAD_LOG || n % LOG_EVERY == 0L) log("BlurProbe: txn.setBackgroundBlurRadius calls=$n radius=$r")
            chain.proceed()
        }
        log("BlurProbe: ✓ Transaction.setBackgroundBlurRadius hooked")
    }
}
