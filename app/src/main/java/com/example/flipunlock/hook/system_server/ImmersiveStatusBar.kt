package com.example.flipunlock.hook.system_server

import android.view.WindowInsets
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.callMethod
import com.example.flipunlock.hook.util.field
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.method
import com.example.flipunlock.hook.util.safeHook
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 外屏默认沉浸：把状态栏从外屏（display 0）拿掉，114px 高度还给应用。
 *
 * 为什么不用现成的路子（实测 2026-11-23，flip2 / OS4.0.3.0）：
 *   - `settings put global policy_control immersive.full=*`
 *     → 外屏 `InsetsSource type=statusBars frame=[0,0][1208,114] visible=true` 一点没变；
 *       refMD `IME_Restrictions.md` §823 在 flip1 上也是"零变化"。这条路放弃。
 *   - SystemUI 侧把状态栏窗口藏起来：只藏视觉、不动 inset，应用拿不到高度，还可能被
 *     `CommandQueue` 的可见性动画拉回来。
 *
 * 采用的机制（framework 自带的 forced-hide 通道）：
 *
 *   DisplayPolicy.updateSystemBarsLw(win, disableFlags)
 *     → InsetsPolicy.updateSystemBars(win, showing, hiding, legacyPolicy)
 *        → mForciblyHidingTypes = hiding
 *     → getStatusControlTargetInner(): `if (areTypesForciblyHidden(statusBars)) return mHidingPermanentControlTarget`
 *     → BarWindow.updateVisibility(): controlTarget.isRequestedVisible()==false → setVisible(false)
 *        → StatusBarManagerInternal.setWindowState(displayId, 1, 2)   ← SystemUI 收状态栏
 *        → InsetsSourceProvider 的 statusBars source 变 invisible → 应用 top inset = 0（真正多出高度）
 *
 * 我们只做一件事：在 `updateSystemBarsLw` 真正执行前，把该 DisplayPolicy 实例的
 * `mHidingPermanentInsetsTypes` 补上 `WindowInsets.Type.statusBars()`。这条通道的几个好性质：
 *   - 通知栏拉下来时 `focusedWin == notificationShade` 在 forced-hide 判断**之前**，
 *     所以下拉通知栏时状态栏照常显示，不会缺一截；
 *   - 顶部下滑触发的 `showTransient(statusBars)` 同样先于 forced-hide 判断 → 沉浸态的
 *     "下滑临时呼出" 保留；
 *   - 只动 display 0（外屏），内屏 display 8 不受影响。
 *
 * 开关：persist.flipunlock.display.immersive（默认 **true**）
 * 生效：重启（hook 在 SystemServerStarting 装）
 *
 * 判据（改完重启后）：
 *   adb shell "dumpsys window | grep 'type=statusBars'"      → visible=false、frame 变空
 *   adb shell "dumpsys window displays | grep -A2 'mDisplayId=0'" → app bounds 顶到 0
 *   adb shell "dumpsys window | grep -i mForciblyHidingTypes"    → statusBars
 */
object ImmersiveStatusBar {

    /** 外屏 displayId（单外屏/双屏都是 0）。 */
    private const val OUTER_DISPLAY = 0

    private var hideTypesField: Field? = null
    private var displayIdMethod: Method? = null
    private var displayContentField: Field? = null
    private var applied = false

    fun hook(param: SystemServerStartingParam) {
        if (!Config.displayImmersive) {
            log("ImmersiveStatusBar: DISABLED by persist.flipunlock.display.immersive")
            return
        }
        safeHook("ImmersiveStatusBar") {
            val policyCls = param.classLoader.loadClass("com.android.server.wm.DisplayPolicy")
            val winStateCls = param.classLoader.loadClass("com.android.server.wm.WindowState")

            val target = policyCls.method("updateSystemBarsLw", winStateCls, Integer.TYPE)
            hideTypesField = runCatching { policyCls.field("mHidingPermanentInsetsTypes") }.getOrNull()
            displayIdMethod = runCatching { policyCls.method("getDisplayId") }.getOrNull()
            if (displayIdMethod == null) {
                // getDisplayId() 若被裁掉，退回到 mDisplayContent.getDisplayId()
                displayContentField = runCatching { policyCls.field("mDisplayContent") }.getOrNull()
            }
            if (hideTypesField == null || (displayIdMethod == null && displayContentField == null)) {
                log("ImmersiveStatusBar: cannot resolve field/method, skip (field=$hideTypesField)")
                return@safeHook
            }

            hook(target) { chain ->
                runCatching { applyForOuterDisplay(chain.thisObject) }
                    .onFailure { log("ImmersiveStatusBar: apply failed", it) }
                chain.proceed()
            }
            log("ImmersiveStatusBar: hooked DisplayPolicy.updateSystemBarsLw → statusBars forced hidden on display $OUTER_DISPLAY")
        }
    }

    private fun applyForOuterDisplay(policy: Any?) {
        if (policy == null) return
        if (displayIdOf(policy) != OUTER_DISPLAY) return
        val f = hideTypesField ?: return

        val current = f.getInt(policy)
        val wanted = current or WindowInsets.Type.statusBars()
        if (wanted != current) {
            f.setInt(policy, wanted)
            log(
                "ImmersiveStatusBar: display $OUTER_DISPLAY force-hide statusBars " +
                    "(mHidingPermanentInsetsTypes 0x${Integer.toHexString(current)} → 0x${Integer.toHexString(wanted)})"
            )
            applied = true
        } else if (!applied) {
            log("ImmersiveStatusBar: already hidden by policy (mHidingPermanentInsetsTypes=0x${Integer.toHexString(current)})")
            applied = true
        }
    }

    private fun displayIdOf(policy: Any): Int {
        displayIdMethod?.let { m ->
            (m.invoke(policy) as? Int)?.let { return it }
        }
        displayContentField?.let { f ->
            val dc = f.get(policy) ?: return -1
            (dc.callMethod("getDisplayId") as? Int)?.let { return it }
        }
        return -1
    }
}
