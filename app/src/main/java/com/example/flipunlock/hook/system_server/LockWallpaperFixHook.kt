package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 锁屏壁纸不再被 ambient 隐藏（2026-09-24 新增，refMD §44.12）。
 *
 * ── 现象 ──
 *   锁屏「没有壁纸」→ 纯黑（内容正常，仅背景黑）。
 *
 * ── 已排除（全部有设备实测证据）──
 *   | 假设 | 实测 | 结论 |
 *   |---|---|---|
 *   | `SystemUiKeyguardFix` 黑层 | `keyguardfix=false` → 控制中心/通知中心恢复，**锁屏仍黑** | ✗ |
 *   | AOD hook | `display.aod=false`（AodHook 完全不装载）→ **仍黑** | ✗ |
 *   | `miui_wallpaper_content_type` | 置 1 → layer 仍 `alpha=0` | ✗ |
 *   | Scrim 全黑 | KEYGUARD 态三 scrim 全 `alpha=0.0` | ✗ |
 *   | 壁纸尺寸 | 已被 `WallpaperFixHook` 钳成 `1392×1392` | ✗ |
 *   | 锁屏壁纸类型 `isFlipTinyScreen?8:2` | `flag_lock_wallpaper_type_8=rotation_image`，类型本就正确 | ✗ |
 *
 * ── 根因链（flip1 ruyi_global 实测，四层）──
 *   ① `com.miui.aod.doze.DozeMachine` 在唤醒后陷入循环（**原生行为，非 hook 造成**：
 *      `aod=false` 时循环计数完全不变）：
 *        UNINITIALIZED→INITIALIZED→DOZE→FINISH→UNINITIALIZED  每 ~1.1s
 *   ② 每轮 `DOZE` 都调 `com.miui.aod.doze.DozeWallpaperState.transitionTo()`
 *      → 其 `case 1..7: z=true`（`DOZE` ordinal 2 命中）→ `mIsAmbientMode = true`
 *   ③ → `IWallpaperManager.setInAmbientMode(true, 500)`
 *   ④ 壁纸引擎 `MiuiKeyguardPictorialWallpaper` 收 ambient=true
 *      → `onVisibilityChanged, visible = false`
 *      → SF: `invisible reason = "alpha = 0 and no blur"` → 锁屏黑
 *
 *   附带：`WallpaperManagerService.extractColors` 对锁屏壁纸恒失败
 *   （`imageWallpaper = mImageWallpaper.equals(getComponent())`，锁屏组件是
 *   `MiuiKeyguardPictorialWallpaper`）→ `SysuiColorExtractor.lock = null` → 渐变全黑。
 *
 * ── 为什么 hook system_server 侧（而非 SystemUI 的 Proxy）──
 *   **实测确认 `MIUIAod.apk` 自带 `android.app.IWallpaperManager` + `$Stub` + `$Stub$Proxy`
 *   的完整定义**（dex 里同时存在定义与引用；`res/aod/.../android/app/IWallpaperManager.java`
 *   反编译产物 83 行含 `class Stub`/`class Proxy`）→
 *   `com.miui.aod.doze.DozeWallpaperState` 用的是**它自己那份 Proxy**，
 *   hook framework 的 `$Stub$Proxy` 拦不住。
 *   `WallpaperManagerService.setInAmbientMode` 是**服务端唯一汇聚点**，
 *   任何客户端（framework Proxy / MIUIAod 自带 Proxy）最终都走它 → 一定拦得到。
 *   先例：同侧的 `WallpaperFixHook`（`WallpaperManagerService.setDimensionHints`）实测生效。
 *
 * ── 本 hook（治标）──
 *   把 `setInAmbientMode(true, ...)` 改写成 `(false, 0)`；`false` 原样放行。
 *
 *   ⚠️ 取舍：AOD 显示时锁屏壁纸**本应**隐藏（`ambient=true` 的原生语义）。
 *   本 hook 会让 AOD 下也保留壁纸。当前 `display.aod=false`（用户已决定），
 *   该副作用不可见；若将来重开 AOD 需重新评估。
 *
 * 进程: system_server
 * 开关: persist.flipunlock.wallpaper.lock（默认 true）
 */
object LockWallpaperFixHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.lockWallpaperFix) {
            log("LockWallpaperFix: DISABLED by persist.flipunlock.wallpaper.lock")
            return
        }
        log("LockWallpaperFix: setting up (system_server)")
        safeHook("LockWallpaperFix") {
            val cls = param.classLoader.loadClass(
                "com.android.server.wallpaper.WallpaperManagerService")
            val method = cls.getDeclaredMethod(
                "setInAmbientMode",
                Boolean::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!)
            method.isAccessible = true

            hook(method) { chain ->
                val inAmbient = chain.args[0] as? Boolean ?: false
                if (inAmbient) {
                    log("LockWallpaperFix: ✓ setInAmbientMode(true) → false (锁屏壁纸不再被隐藏)")
                    chain.proceed(arrayOf<Any?>(false, 0L))
                } else {
                    chain.proceed()
                }
            }
            log("LockWallpaperFix: ✓ hooked WallpaperManagerService.setInAmbientMode")
        }
    }
}
