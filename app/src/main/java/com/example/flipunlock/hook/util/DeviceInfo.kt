package com.example.flipunlock.hook.util

/**
 * 机型判断工具类(2026-08-14 从 HookUtils 提取为独立类)。
 *
 * 识别依据(优先 ro.product.device 厂商代号, 稳定不随固件变; model 前缀兜底):
 *   flip1 "ruyi"  = 2405CPX3DC (Xiaomi MIX Flip)
 *   flip2 "bixi"  = 2505APX7BC (Xiaomi MIX Flip 2)
 *
 * 用途: 区分 flip1 专用 hook(如 SystemUiKeyguardFix)与 flip2 专用逻辑(布局保护等),
 *       以及 Main.kt 的机型分支(CutoutRemove 仅 flip2 等)。
 */
object DeviceInfo {

    private val device: String by lazy { prop("ro.product.device") }
    private val model: String by lazy { prop("ro.product.model") }

    /** flip1(ruyi / 2405*): Xiaomi MIX Flip */
    val isFlip1: Boolean get() = device == "ruyi" || model.startsWith("2405")

    /** flip2(bixi / 2505*): Xiaomi MIX Flip 2 */
    val isFlip2: Boolean get() = device == "bixi" || model.startsWith("2505")

    /** ro.product.model 原值(日志/诊断用) */
    val deviceModel: String get() = model

    private fun prop(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getDeclaredMethod("get", String::class.java, String::class.java)
            .invoke(null, key, "") as? String
    }.getOrNull() ?: ""
}

/** 顶层函数: 兼容各 hook 的 `import util.isFlip1Device` 调用点 */
fun isFlip1Device(): Boolean = DeviceInfo.isFlip1
fun isFlip2Device(): Boolean = DeviceInfo.isFlip2
fun deviceModel(): String = DeviceInfo.deviceModel
