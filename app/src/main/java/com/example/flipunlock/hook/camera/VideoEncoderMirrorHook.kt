package com.example.flipunlock.hook.camera

import android.media.MediaFormat
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.currentProcessName
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.processClassLoader
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 录像成片去镜像（路线 A，2026-11-27）：**保留原生 `selfieUnfold=1`**（预览镜像但方向正确），
 * 只在**编码器**上把画面左右翻回来，这样成片不镜像、预览方向不变。
 *
 * ## 背景
 * `selfieUnfold` 一个参数同时控「预览镜像」和「外屏 180° 补偿」（见 refMD `Camera.md §13.10.5`）：
 * - `1`（原生）：预览镜像、方向对；照片正常；**录像镜像**。
 * - `0/2`：预览不镜像但翻 180°；照片要额外补偿。
 * ⇒ 想要「预览镜像 + 成片正常」，只能改编码器。
 *
 * ## 做法
 * 相机用的是 `kq.t`（CED MiMediaCodecRecorder）→ `kq.e.call()` 组好 `MediaFormat` → `kq.h.a()` 配编码器。
 * 本钩直接钩 `android.media.MediaCodec.configure(MediaFormat, …)`：对 `video/*` 的 format
 * 写入 `vendor.qti-ext-enc-preprocess-mirror.flip = <persist.flipunlock.camera.video_mirror_flip>`。
 * 该键就是 `kq.e` 从 `video-param-mirror-state` 解析出来写的那个（QTI 编码器预处理翻转）。
 *
 * ## 开关
 * `persist.flipunlock.camera.video_mirror_flip`：**-1 = 关闭**（不干预）；1/2/3 = 翻转位。
 * 具体哪个值是「左右翻」需要实机 A/B（1=竖翻、2=横翻、3=两者，按 QTI 习惯，未确认）。
 * 改完 `am force-stop com.android.camera`。
 */
object VideoEncoderMirrorHook : BaseHook() {

    override val targetPackages = listOf("com.android.camera")

    private const val TAG = "VideoEncMirror"
    private const val KEY = "vendor.qti-ext-enc-preprocess-mirror.flip"

    override fun setupHooks(param: PackageReadyParam) {
        val process = currentProcessName()
        if (process != "com.android.camera") {
            log("$TAG: skip, process=$process")
            return
        }
        val flip = Config.videoMirrorFlip
        if (flip < 0) {
            log("$TAG: off（persist.flipunlock.camera.video_mirror_flip 未设）")
            return
        }
        val cl = runCatching { processClassLoader(param.classLoader) }.getOrDefault(param.classLoader)
        var n = 0
        runCatching {
            val cls = cl.loadClass("android.media.MediaCodec")
            cls.declaredMethods.filter { it.name == "configure" }.forEach { m ->
                m.isAccessible = true
                runCatching {
                    hook(m) { chain ->
                        val fmt = chain.args.getOrNull(0) as? MediaFormat
                        val mime = fmt?.getString("mime")
                        if (fmt != null && mime != null && mime.startsWith("video/")) {
                            fmt.setInteger(KEY, flip)
                            log("$TAG: $mime 写入 $KEY=$flip")
                        }
                        chain.proceed()
                    }
                    n++
                }.onFailure { log("$TAG: configure(${m.parameterCount}) hook failed: ${it.message}") }
            }
        }.onFailure { log("$TAG: load MediaCodec failed: ${it.message}") }
        if (n == 0) log("$TAG: !! MediaCodec.configure 一个都没钩上")
        else log("$TAG: ✓ MediaCodec.configure × $n hooked → flip=$flip")
    }
}
