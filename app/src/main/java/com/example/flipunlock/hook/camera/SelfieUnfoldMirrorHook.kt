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
 * 外屏录像「镜像」的真正源头：session 参数 `com.xiaomi.sessionparams.selfieUnfold`�?026-11-27）�? *
 * ## 现象
 * 折叠/单外屏，系统相机录出来的 **mp4 左右�?*（文字反、二维码反），而同一场景�?**照片正常**�? * 取景预览同样是反的�? *
 * ## 实测证据（flip2 / OS4.0.3 / 3.27-unlock2，pref 分别 true/false 各录一次）
 * ```
 * MCAM_VideoModuleDevice: updateFrontMirrorEnabledParameter: parameter = false   �?xiaomi.flip.enabled=false
 * MCAM_VideoModuleDevice: updatePreviewMirrorParam: 1                            �?就是本钩要压�?session 参数
 * MCAM_CaptureRequestBuilder: applyFrontMirror: false                            �?录像 request 不翻
 * ```
 * 抓帧�?mp4 确实左右反、容�?tkhd 无负行列�?�?�?HAL �?session 参数把预�?录像整条翻了�? *
 * ## 为什么恒 1
 * `z3.d.x(k)`（视频走 `VideoModuleDevice`）：
 * `int i = ((Se.b.Y() && K2.m.b()) || K2.b.e0()) ? 1 : 0;`
 * 其中 `K2.b.e0() = X() || c0() || d0()`，`d0() = (o() == SECOND_SCREEN_MODE)` —�? * 单外屏就�?SECOND_SCREEN_MODE �?�?true。与「自拍镜像」pref 无关（pref 只管照片）�? *
 * ## 做法
 * �?session 参数写点 `n9.z1.a(B0 key, T value)`（另一�?applier `oa.g.c` 一并钩），
 * �?**B0 静态字段身�?* 认出 `ka.w0.T`�? selfieUnfold 的包装），把值改�?0�? * 纯运行时，不写磁盘；卸载即恢复�? *
 * ## 开�? * `persist.flipunlock.camera.no_selfie_unfold_mirror`（默�?**true**），改完 `am force-stop com.android.camera`�? */
object SelfieUnfoldMirrorHook : BaseHook() {

    override val targetPackages = listOf("com.android.camera")

    private const val TAG = "UnfoldMirror"

    /** `ka.w0` �?selfieUnfold 的字段名（反编译�?`T`）。运行时若识别不到会只打日志�?*/
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
        log("$TAG: ka.w0 B0 静态字�?${fields.size} �? ${fields.joinToString(",") { it.first }}")
        val n = hookSetter(cl, "n9.z1", "a", fields) + hookSetter(cl, "oa.g", "c", fields)
        if (n == 0) log("$TAG: !! 没有钩到 session 参数写点（类名可能变了）")
        // 照片补偿：selfieUnfold=0 之后 HAL 反而会�?still 加镜像，�?xiaomi.flip.enabled=true 抵消�?        // �?tag 的来源是 isFrontMirror() �?data.data.v.U()�?自拍镜像" pref），所以这里把 U() 钉成 true�?        // 实测�?.30）：hook 开 + pref=false �?照片反而镜像；hook 开 + pref=true �?照片正常�?        // 副作用：相机设置里的「自拍镜像」勾选状态会显示为开（默认本来就�?true）�?        runCatching {
            val cls = cl.loadClass("com.android.camera.data.data.v")
            val m = cls.getDeclaredMethod("U").apply { isAccessible = true }
            hook(m, replaceResult(true))
            log("$TAG: �?data.data.v.U() �?true（照片镜像补偿）")
        }.onFailure { log("$TAG: data.data.v.U() hook failed: ${it.message}") }
    }

    /** �?`ka.w0` 的所�?B0 静态字段读出来（fieldName -> 对象），用于按对象身份认 key�?*/
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
                if (value is Int && value != 0 && field in TARGET_FIELDS) {
                    log("$TAG: selfieUnfold($field) $value �?0�?clsName.$methodName�?)
                    return@hook chain.proceed(arrayOf(keyObj, 0))
                }
                chain.proceed()
            }
            log("$TAG: �?$clsName.$methodName hooked")
            1
        }.getOrElse {
            log("$TAG: $clsName.$methodName hook failed: ${it.message}")
            0
        }
    }
}
