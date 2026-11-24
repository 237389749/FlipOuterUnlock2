package com.example.flipunlock.hook.system_server

import android.content.Intent
import android.content.pm.ResolveInfo
import com.example.flipunlock.hook.util.callMethod
import com.example.flipunlock.hook.util.getField
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.method
import com.example.flipunlock.hook.util.safeHook
import com.example.flipunlock.hook.util.setField
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 把系统默认安装器从 MIUI 版换成 AOSP 版（并让 MIUI 版退位）。
 *
 * 为什么需要 hook：这件事由**两个互相独立的机制**控制，缺一不可。
 *   ① 状态层：`PackageManagerServiceImpl.updateDefaultPkgInstallerLocked()`（国内版 else 分支）
 *      每次开机把 AOSP 安装器 `setInstalled(false, 0)`，并把 MIUI 写回 `mPackages`。
 *      调用点只有 `initBeforeScanNonSystemApps()` —— 这就是"装回来重启又没了"的原因。
 *   ② 路由层：`hookChooseBestActivity()` 在 `!isCTS()` 时把 APK 的 VIEW 意图
 *      `setPackage("com.miui.packageinstaller")` **写死**。只做①不做②，
 *      `cmd package query-activities` 仍只会命中 MIUI（实测）。
 *
 * 为什么不能走常规手段：MIUI 安装器 `coreApp="true"`，
 *   `pm uninstall` → `DELETE_FAILED_INTERNAL_ERROR`，
 *   `pm disable-user` → `SecurityException: Cannot disable required package`；
 *   两个"必需包"字段（`mRequiredInstallerPackage` / `mRequiredUninstallerPackage`）也要跟着挪到 AOSP，
 *   否则会出现"AOSP 在用、MIUI 那个依然禁不掉"的半吊子状态。改分区是 AVB 红线，不做。
 *
 * 实现取舍（见 find/installer_swap_spec.md §5.3）：
 *   - 只用 `PackageSetting.setInstalled(true, 0)` 改状态，**不走** `installExistingPackageAsUser()`
 *     —— 后者会重入 PM 安装流程，而本 hook 所处的初始化阶段大概率已持 `mPms.mLock`（保守选择）。
 *   - 顺序：先启用 AOSP（最坏情况也是 AOSP 可用）→ 再摘 MIUI → 最后改 current / required。
 *   - 全程 runCatching + 日志，任何异常都不许打断 PM 初始化。
 *
 * 开关：`persist.flipunlock.installer.swap`（默认 true；设为 false = 完全原生，重启生效）
 * 进程：system_server
 * 回滚：关掉开关或禁用模块 + 重启（全部是内存态改动，没有持久化写入）
 */
object PackageInstallerSwap {

    private const val TAG = "InstallerSwap"
    private const val AOSP = "com.android.packageinstaller"
    private const val MIUI = "com.miui.packageinstaller"
    private const val MIME = "application/vnd.android.package-archive"

    /** 组件启用状态（PackageManager.COMPONENT_ENABLED_STATE_*）。 */
    private const val ENABLED_DISABLED_USER = 3

    @Volatile
    private var dumpedOnce = false

    fun hook(param: SystemServerStartingParam) {
        if (!com.example.flipunlock.hook.util.Config.installerSwap) {
            log("$TAG: DISABLED by persist.flipunlock.installer.swap")
            return
        }

        val cls = runCatching {
            param.classLoader.loadClass("com.android.server.pm.PackageManagerServiceImpl")
        }.getOrNull()
        if (cls == null) {
            log("$TAG: PackageManagerServiceImpl not found -> skip")
            return
        }

        // ── A. 状态层：ROM 跑完自己的 else 分支之后，再纠正成 CTS 分支的效果 ──
        runCatching { cls.method("updateDefaultPkgInstallerLocked") }.onSuccess { m ->
            safeHook("$TAG/A") {
                hook(m) { chain ->
                    val result = chain.proceed()
                    runCatching { applyState(chain.thisObject) }
                        .onFailure { log("$TAG: applyState failed", it) }
                    result
                }
                log("$TAG: hook A installed (updateDefaultPkgInstallerLocked)")
            }
        }.onFailure { log("$TAG: hook A NOT installed: ${it.message}") }

        // ── B. 路由层：原方法把 APK VIEW 意图写死成 MIUI，这里用 Intent 副本重解析 ──
        runCatching {
            cls.method(
                "hookChooseBestActivity",
                Intent::class.java,
                String::class.java,
                Long::class.javaPrimitiveType!!,
                List::class.java,
                Int::class.javaPrimitiveType!!,
                ResolveInfo::class.java
            )
        }.onSuccess { m ->
            safeHook("$TAG/B") {
                hook(m) { chain ->
                    val original = chain.proceed()
                    runCatching { reroute(chain.thisObject, chain.args, original) }
                        .getOrDefault(original)
                }
                log("$TAG: hook B installed (hookChooseBestActivity)")
            }
        }.onFailure { log("$TAG: hook B NOT installed: ${it.message}") }
    }

    /** 把状态改成「AOSP 安装器在用、MIUI 摘表」；幂等，可重复执行。 */
    private fun applyState(impl: Any?) {
        if (impl == null) return
        val settings = impl.getField("mPkgSettings") ?: return
        val pms = impl.getField("mPms") ?: return
        @Suppress("UNCHECKED_CAST")
        val settingsPkgs = settings.getField("mPackages") as? MutableMap<Any?, Any?> ?: return
        @Suppress("UNCHECKED_CAST")
        val pmsPkgs = pms.getField("mPackages") as? MutableMap<Any?, Any?> ?: return

        val aospPs = settingsPkgs[AOSP]
        if (aospPs == null) {
            // 不变量 #4：取不到 AOSP 的 PackageSetting 就整体放弃，否则会出现"两个安装器都不可用"
            log("$TAG: AOSP not in Settings.mPackages -> abort (nothing changed)")
            return
        }

        // ① 先启用 AOSP（中途抛异常时最坏结果也是 AOSP 可用）
        aospPs.callMethod("setInstalled", true, 0)
        // enabled 状态不受 setInstalled 影响；被人手工 disable 过就复位
        runCatching {
            val enabled = aospPs.callMethod("getEnabled") as? Int
            if (enabled == ENABLED_DISABLED_USER) {
                aospPs.callMethod("setEnabled", 0, 0)
                log("$TAG: AOSP was disabled-user -> re-enabled")
            }
        }.onFailure { log("$TAG: enabled-state check skipped: ${it.message}") }

        // ② 摘掉 MIUI（照 ROM CTS 分支的手法：setInstalled(false) + 两个 map remove）
        val miuiPs = settingsPkgs[MIUI]
        if (miuiPs != null) {
            runCatching { impl.setField("mMiuiInstallerPackageSetting", miuiPs) }
            val miuiPkg = pmsPkgs[MIUI]
            if (miuiPkg != null) runCatching { impl.setField("mMiuiInstallerPackage", miuiPkg) }
            miuiPs.callMethod("setInstalled", false, 0)
            settingsPkgs.remove(MIUI)
            pmsPkgs.remove(MIUI)
            runCatching {
                if (settings.callMethod("isDisabledSystemPackageLPr", MIUI) == true) {
                    settings.callMethod("removeDisabledSystemPackageLPw", MIUI)
                }
            }.onFailure { log("$TAG: disabled-system-package cleanup skipped: ${it.message}") }
        }

        // ③ 当前安装器
        impl.setField("mCurrentPackageInstaller", AOSP)

        // ④ 两个"必需包"字段跟着挪 —— 挪完 MIUI 才不再是 required（它原本就是拒绝 disable 的原因）
        val ri = runCatching { pms.setField("mRequiredInstallerPackage", AOSP) }
        val ru = runCatching { pms.setField("mRequiredUninstallerPackage", AOSP) }
        if (ri.isFailure && ru.isFailure) {
            log("$TAG: required-installer fields not found on this ROM (skip)")
        }

        if (!dumpedOnce) {
            dumpedOnce = true
            log(
                "$TAG: state applied -> $AOSP installed=true, $MIUI removed from maps, " +
                    "current=$AOSP, required=$AOSP"
            )
        }
    }

    /** 只处理 ACTION_VIEW + APK MIME；用 Intent 副本重解析，失败保留原返回值。 */
    private fun reroute(impl: Any?, args: List<Any?>, original: Any?): Any? {
        val intent = args.getOrNull(0) as? Intent ?: return original
        if (intent.action != Intent.ACTION_VIEW) return original
        val resolvedType = args.getOrNull(1) as? String
        val intentType = intent.type
        if (intentType != MIME && resolvedType != MIME) return original

        val flags = (args.getOrNull(2) as? Long) ?: 0L
        val userId = (args.getOrNull(4) as? Int) ?: 0
        val pm = impl?.getField("mPM") ?: return original

        val already = (original as? ResolveInfo)?.activityInfo?.packageName
        if (already == AOSP) return original

        // 用副本，避免污染调用方传进来的 intent
        val probe = Intent(intent).setPackage(AOSP)
        val resolved = pm.callMethod("resolveIntent", probe, resolvedType, flags, userId) ?: return original
        log("$TAG: APK VIEW rerouted -> $AOSP (was ${already ?: "null"})")
        return resolved
    }
}
