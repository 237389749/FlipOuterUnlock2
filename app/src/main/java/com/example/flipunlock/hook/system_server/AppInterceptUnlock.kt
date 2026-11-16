package com.example.flipunlock.hook.system_server

import android.content.ComponentName
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * OS4.0.3 起的外屏启动拦截解除 —— **新版本专用通道**（2026-11-16）。
 *
 * ── 为什么单开一个文件（而不是改 AppRestriction/AppWhitelist）────────────────
 * 旧的 `AppRestriction` / `AppWhitelist` 在 `Main.kt` 里是**注释掉的**，所以它们"空转"
 * 跟上不上场没关系；而且 4.0.3 上**类名和签名都没变**（见下面的实证），返工旧文件没有必要。
 * 新版本真正的变化是**装载方式**：`InterceptActivityController` 不再在主 classpath，而是由
 * `MiuiStubRegistry.loadJar()` 从独立 jar 动态加载 —— 所以老的 `param.classLoader.loadClass`
 * 必然抛 `ClassNotFoundException` 被吞掉（静默空转）。本文件专门走新版本的装载路径。
 *
 * ── 实证（2026-11-16，本机 decompile）────────────────────────────────────────
 *   `MiuiAppContinuityLoader`（**在 services.jar，主 classpath，必然可加载**）:
 *       CLASS_PATH = "/system_ext/framework/miui-appcompat.appcontinuity.jar"
 *       ClassLoader cl = MiuiStubRegistry.loadJar(CLASS_PATH, true, <loader>);
 *       MiuiStubRegistry.collectImpl("com.android.server.wm.MiuiAppContinuityImplCollector", cl);
 *   → 目标类在 `miui-appcompat.appcontinuity.jar` 里，**类名没变**：
 *       com.android.server.wm.InterceptActivityController
 *     methods: boolean isInterceptList(ActivityRecord) / isInterceptList(ComponentName)
 *              boolean isInterceptListUnCheckFold(ComponentName)      ← 唯一咽喉点
 *   ⚠️ 上一份 HANDOFF 写的"拦截类换成了 FlipPolicyActivityIntercept"**是误读**：
 *      `FlipPolicyActivityIntercept` 只是**日志 TAG**，取自
 *      `ContinuityPolicyService.ACTIVITY_INTERCEPT_TAG`；类还是 `InterceptActivityController`。
 *
 * ── 逻辑方向（反编译确认，别弄反）───────────────────────────────────────────
 *   onForegroundActivityChangedLocked:
 *       if (!isInterceptListUnCheckFold(component)) return;   // ← 不拦截
 *       ... moveToBack + StartActivityTipView.show()          // ← 拦截（"展开到内屏继续操作"）
 *   ⇒ hook 成 **false** = 早退 = 不拦截。`isInterceptList(ComponentName)` 也在这条线上
 *     （`sFolded ? isInterceptListUnCheckFold(...) : false`），所以抓咽喉点一个就够。
 *
 * ── 为什么身份 1 下"本来就不拦"──────────────────────────────────────────────
 *   总闸是 `ContinuityPolicyService.sFolded`：身份 1 时它恒 false ⇒ `isInterceptList()` 直接
 *   返回 false ⇒ 拦截根本不发生。所以本 hook 在单外屏 + 身份 1 下是**空转但无害**的；
 *   它的价值是**身份一旦回到 4（或走 NoProp 属性 4 路线）仍然能解除拦截**。
 *
 * 进程: system_server
 * 开关: persist.flipunlock.app.intercept（默认 true，仅新版本路径生效）
 * 守护: isFlip2Device() && Config.singleOuter（与 HANDOFF 的双守护一致）
 */
object AppInterceptUnlock {

    private const val CLS_CONTROLLER = "com.android.server.wm.InterceptActivityController"
    private const val CLS_STUB_REGISTRY = "com.miui.base.MiuiStubRegistry"
    private const val CLS_CONTINUITY_LOADER = "com.android.server.wm.MiuiAppContinuityLoader"
    private const val JAR_MARK = "appcontinuity"

    /** 只装一次（loadJar / collectImpl / 兜底 可能都命中） */
    @Volatile
    private var installed = false

    fun hook(param: SystemServerStartingParam) {
        if (!Config.appIntercept) {
            log("AppInterceptUnlock: DISABLED by persist.flipunlock.app.intercept")
            return
        }
        if (!isFlip2Device()) {
            log("AppInterceptUnlock: skip (not flip2)")
            return
        }
        if (!Config.singleOuter) {
            log("AppInterceptUnlock: skip (singleOuter=false)")
            return
        }
        log("AppInterceptUnlock: setting up (OS4.0.3 新版本通道)")

        val captured = captureNewPathLoaders()
        if (!captured) {
            log("AppInterceptUnlock: 新版本装载器未命中 -> 退回主 classloader 直连（老版本路径）")
        }
        // 老版本 / 装载器已跑过的情况：主 classloader 直接试试
        installOn(param.classLoader, source = "param.classLoader")
        // 最后再补一刀：万一 jar 已在别处加载完，碰碰运气
        runCatching { Class.forName(CLS_CONTROLLER) }.getOrNull()?.let {
            installOn(it.classLoader, source = "Class.forName")
        }
    }

    /**
     * 新版本路径：抓 `MiuiStubRegistry.loadJar(...)` 返回的 child ClassLoader。
     * 该方法在 `MiuiAppContinuityLoader` 里被调用，签名 (String path, boolean, ClassLoader parent)。
     */
    private fun captureNewPathLoaders(): Boolean {
        val reg = runCatching { Class.forName(CLS_STUB_REGISTRY) }.getOrNull()
        if (reg == null) {
            log("AppInterceptUnlock: $CLS_STUB_REGISTRY 不可加载 -> 不是新版本装载机制")
            return false
        }
        var hookedAny = false

        // ① loadJar 的返回值就是 jar 的 ClassLoader
        runCatching {
            val m = reg.method(
                "loadJar",
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
                ClassLoader::class.java,
            )
            hook(m) { chain ->
                val result = chain.proceed()
                val path = chain.args.getOrNull(0) as? String
                if (result is ClassLoader && path != null && path.contains(JAR_MARK)) {
                    log("AppInterceptUnlock: 捕获 jar ClassLoader via loadJar($path)")
                    installOn(result, source = "loadJar")
                }
                result
            }
            hookedAny = true
            log("AppInterceptUnlock: ✓ $CLS_STUB_REGISTRY.loadJar hooked")
        }.onFailure { log("AppInterceptUnlock: loadJar hook failed: ${it.message}") }

        // ② collectImpl(String, ClassLoader) —— 紧随 loadJar 之后，双保险
        runCatching {
            val m = reg.method("collectImpl", String::class.java, ClassLoader::class.java)
            hook(m) { chain ->
                val cl = chain.args.getOrNull(1) as? ClassLoader
                if (cl != null) installOn(cl, source = "collectImpl")
                chain.proceed()
            }
            hookedAny = true
            log("AppInterceptUnlock: ✓ $CLS_STUB_REGISTRY.collectImpl hooked")
        }.onFailure { log("AppInterceptUnlock: collectImpl hook failed: ${it.message}") }

        // ③ 兜底：loader 类自己（services.jar，必可加载）——它跑完说明 jar 已就绪
        runCatching {
            val loaderCls = Class.forName(CLS_CONTINUITY_LOADER)
            val init = loaderCls.method("init")
            hook(init) { chain ->
                val r = chain.proceed()
                runCatching { Class.forName(CLS_CONTROLLER) }.getOrNull()
                    ?.let { installOn(it.classLoader, source = "MiuiAppContinuityLoader.init") }
                r
            }
            hookedAny = true
            log("AppInterceptUnlock: ✓ $CLS_CONTINUITY_LOADER.init hooked")
        }.onFailure { log("AppInterceptUnlock: continuity loader hook failed: ${it.message}") }

        return hookedAny
    }

    /** 在指定 classloader 上装真正的 hook；装过就不再装。 */
    private fun installOn(cl: ClassLoader, source: String) {
        if (installed) return
        val cls = runCatching { cl.loadClass(CLS_CONTROLLER) }.getOrNull()
        if (cls == null) {
            log("AppInterceptUnlock: [$source] $CLS_CONTROLLER 不可加载")
            return
        }
        val m = runCatching { cls.method("isInterceptListUnCheckFold", ComponentName::class.java) }
            .getOrElse {
                log("AppInterceptUnlock: [$source] isInterceptListUnCheckFold(ComponentName) 找不到: ${it.message}")
                return
            }
        hook(m, replaceResult(false))
        installed = true
        log(
            "AppInterceptUnlock: ✓ [$source] ${cls.name}.isInterceptListUnCheckFold → false" +
                " (loader=${cl.javaClass.simpleName})"
        )
    }
}
