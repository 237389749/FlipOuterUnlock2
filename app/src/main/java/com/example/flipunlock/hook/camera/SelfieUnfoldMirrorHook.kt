package com.example.flipunlock.hook.camera

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.processClassLoader
import com.example.flipunlock.hook.util.replaceResult
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Modifier

/**
 * 外屏录像「镜像」的真正源头：session 参数 `com.xiaomi.sessionparams.selfieUnfold`（2026-11-27）。
 *
 * ## 现象
 * 折叠/单外屏，系统相机录出来的 **mp4 左右反**（文字反、二维码反），而同一场景的 **照片正常**；
 * 取景预览同样是反的。
 *
 * ## 实测证据（flip2 / OS4.0.3）
 * ```
 * MCAM_VideoModuleDevice: updateFrontMirrorEnabledParameter: parameter = false   ← xiaomi.flip.enabled=false
 * MCAM_VideoModuleDevice: updatePreviewMirrorParam: 1                            ← 本钩要压的 session 参数
 * MCAM_CaptureRequestBuilder: applyFrontMirror: false                            ← 录像 request 不翻
 * ```
 * 抓帧后 mp4 确实左右反、容器 tkhd 无负行列式 ⇒ 是 HAL 按 session 参数把预览+录像整条翻了。
 *
 * ## 为什么恒 1
 * `z3.d.x(k)`（视频走 `VideoModuleDevice`）：
 * `int i = ((Se.b.Y() && K2.m.b()) || K2.b.e0()) ? 1 : 0;`
 * 其中 `K2.b.e0() = X() || c0() || d0()`，`d0() = (o() == SECOND_SCREEN_MODE)` ——
 * 单外屏就是 SECOND_SCREEN_MODE ⇒ 恒 true。与「自拍镜像」pref 无关（pref 只管照片）。
 *
 * ## 做法
 * 1. 钩 session 参数写点 `n9.z1.a(B0 key, T value)`（另一条 applier `oa.g.c` 一并钩），
 *    用 **B0 静态字段身份** 认出 `ka.w0.T`（= selfieUnfold 的包装），把值改成 0。
 * 2. 补偿：`selfieUnfold=0` 后 HAL 反而给 still 加镜像 → 同时把 `data.data.v.U()` 钉成 true
 *    （该 pref 是照片 `xiaomi.flip.enabled` tag 的来源，true 正好抵消）。
 *    实测：hook 开 + pref=false → 照片反而镜像；hook 开 + pref=true → 照片正常；
 *    U() 钉 true 后与磁盘上的 pref 无关。
 *
 * 纯运行时，不写磁盘；卸载即恢复。
 *
 * ## 开关
 * `persist.flipunlock.camera.no_selfie_unfold_mirror`（默认 **true**），改完 `am force-stop com.android.camera`。
 */
object SelfieUnfoldMirrorHook : BaseHook() {

    override val targetPackages = listOf("com.android.camera")

    private const val TAG = "UnfoldMirror"

    /** `ka.w0` 里 selfieUnfold 的字段名（反编译为 `T`）。 */
    private val TARGET_FIELDS = setOf("T")

    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())

    override fun setupHooks(param: PackageReadyParam) {
        val process = currentProcessName()
        if (process != "com.android.camera") {
            log("$TAG: skip, process=$process")
            return
        }
        if (!Config.cameraNoSelfieUnfoldMirror) {
            log("$TAG: skip, persist.flipunlock.camera.no_selfie_unfold_mirror=0")
            return
        }
        val cl = runCatching { processClassLoader(param.classLoader) }.getOrDefault(param.classLoader)
        val fields = runCatching { b0StaticFields(cl) }.getOrDefault(emptyList())
        log("$TAG: ka.w0 B0 静态字段 ${fields.size} 个: ${fields.joinToString(",") { it.first }}")
        val forced = Config.selfieUnfoldValue
        log("$TAG: 强制 selfieUnfold = $forced")
        val n = hookSetter(cl, "n9.z1", "a", fields, forced) + hookSetter(cl, "oa.g", "c", fields, forced)
        if (n == 0) log("$TAG: !! 没有钩到 session 参数写点（类名可能变了）")

        // 照片补偿：U() → true，等价于「自拍镜像」pref 勾上，抵消 selfieUnfold=0 带来的 still 镜像。
        // 只在 forced==0 时挂（其他取值语义未确认，别乱补偿）。
        if (forced == 0) {
            runCatching {
                val cls = cl.loadClass("com.android.camera.data.data.v")
                val m = cls.getDeclaredMethod("U").apply { isAccessible = true }
                hook(m, replaceResult(true))
                log("$TAG: ✓ data.data.v.U() → true（照片镜像补偿）")
            }.onFailure { log("$TAG: data.data.v.U() hook failed: ${it.message}") }
        }
    }

    /** 把 `ka.w0` 的所有 B0 静态字段读出来（fieldName -> 对象），用于按对象身份认 key。 */
    private fun b0StaticFields(cl: ClassLoader): List<Pair<String, Any?>> {
        val b0 = cl.loadClass("ka.B0")
        val w0 = cl.loadClass("ka.w0")
        return w0.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && b0.isAssignableFrom(it.type) }
            .mapNotNull { f ->
                runCatching {
                    f.isAccessible = true
                    f.name to f.get(null)
                }.getOrNull()
            }
    }

    private fun hookSetter(
        cl: ClassLoader,
        clsName: String,
        methodName: String,
        fields: List<Pair<String, Any?>>,
        forced: Int,
    ): Int {
        return runCatching {
            val cls = cl.loadClass(clsName)
            val m = cls.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterCount == 2
            }
            if (m == null) {
                log("$TAG: $clsName.$methodName(2 args) 不存在，跳过")
                return 0
            }
            m.isAccessible = true
            hook(m) { chain ->
                val args = chain.args
                val value = args.getOrNull(1)
                val keyObj = args.getOrNull(0)
                val field = fields.firstOrNull { it.second === keyObj }?.first
                if (value is Int) {
                    val tag = "$clsName.$methodName:${field ?: "?"}=$value"
                    if (seen.add(tag)) log("$TAG: seen $tag")
                }
                if (value is Int && field in TARGET_FIELDS && value != forced) {
                    log("$TAG: selfieUnfold($field) $value → $forced（$clsName.$methodName）")
                    return@hook chain.proceed(arrayOf(keyObj, forced))
                }
                chain.proceed()
            }
            log("$TAG: ✓ $clsName.$methodName hooked → $forced")
            1
        }.getOrElse {
            log("$TAG: $clsName.$methodName hook failed: ${it.message}")
            0
        }
    }
}
