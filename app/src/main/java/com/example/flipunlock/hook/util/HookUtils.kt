package com.example.flipunlock.hook.util

import android.util.Log
import com.example.flipunlock.module
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Executable

private const val LOG_TAG = "FlipOuterUnlock"

/** Walk up the classloader hierarchy until the class is found. */
internal fun ClassLoader.findClassUp(name: String): Class<*>? {
    var cl: ClassLoader? = this
    while (cl != null) {
        try { return cl.loadClass(name) }
        catch (_: ClassNotFoundException) { cl = cl.parent }
    }
    return null
}

internal fun safeHook(name: String, block: () -> Unit) {
    runCatching(block).onFailure { log("[$name] failed", it) }
}

internal fun currentProcessName(): String? = runCatching {
    val at = Class.forName("android.app.ActivityThread")
    at.getMethod("currentProcessName").invoke(null) as? String
}.getOrNull()

/** 机型判断: flip1(2405CPX3DC, MIX Flip) / flip2(2505APX7BC, MIX Flip 2)。
 *  用于区分 flip1 专用 hook(如 SystemUiKeyguardFix, flip2 有 Dummy 保护不需要)。 */
internal fun deviceModel(): String = runCatching {
    Class.forName("android.os.SystemProperties")
        .getDeclaredMethod("get", String::class.java, String::class.java)
        .invoke(null, "ro.product.model", "") as? String
}.getOrNull() ?: ""

internal fun isFlip1Device(): Boolean = deviceModel().startsWith("2405")
internal fun isFlip2Device(): Boolean = deviceModel().startsWith("2505")

/** 进程主 classLoader（systemui 等 persistent 进程在 pkg=android 回调时,
 *  param.classLoader 是系统框架,不含 APK 类——用进程 Application 的 classLoader 替代）。 */
internal fun processClassLoader(fallback: ClassLoader): ClassLoader {
    return runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val app = at.getMethod("currentApplication").invoke(null)
        val cl = app?.javaClass?.getMethod("getClassLoader")?.invoke(app) as? ClassLoader
        cl
    }.getOrNull() ?: fallback
}

internal fun log(msg: String, e: Throwable? = null) {
    // Use android.util.Log directly — LSPosed module.log() may not go to logcat
    if (e != null) Log.e(LOG_TAG, msg, e) else Log.e(LOG_TAG, msg)
}

internal fun hook(origin: Executable, hooker: Hooker): HookHandle =
    module!!.hook(origin).intercept(hooker)

internal fun hook(origin: Executable, priority: Int, hooker: Hooker): HookHandle =
    module!!.hook(origin).setPriority(priority).intercept(hooker)

internal fun replaceResult(value: Any?): Hooker = Hooker { value }

internal fun after(block: (Chain, Any?) -> Any?): Hooker = Hooker { chain ->
    val result = chain.proceed()
    block(chain, result)
}

internal fun before(block: (Chain) -> Unit): Hooker = Hooker { chain ->
    block(chain)
    chain.proceed()
}

internal inline fun <T> runWithCleanup(cleanup: () -> Unit, block: () -> T): T {
    return runCatching(block).also { cleanup() }.getOrThrow()
}

internal fun createDexKitBridge(classLoader: ClassLoader): DexKitBridge {
    System.loadLibrary("dexkit")
    return DexKitBridge.create(classLoader, false)
}

internal fun hookScope(origin: Executable, activeHooker: (Chain) -> Any?): HookScope {
    val active = ThreadLocal<Boolean>()
    hook(origin) { chain ->
        if (active.get() == true) activeHooker(chain) else chain.proceed()
    }
    return HookScope(active)
}

internal class HookScope(private val active: ThreadLocal<Boolean>) {
    fun <T> run(block: () -> T): T {
        active.set(true)
        return runWithCleanup({ active.remove() }, block)
    }

    fun stopOn(origin: Executable): HookScope {
        hook(origin) { chain ->
            active.remove()
            chain.proceed()
        }
        return this
    }
}

