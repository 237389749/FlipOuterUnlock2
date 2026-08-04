package com.example.flipunlock.hook.system_server

import android.content.Context
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * IME freedom on outer screen — system_server hooks.
 *
 * Three hooks on InputMethodManagerServiceImpl:
 *   #1 shouldShowCurrentInput(Context) → true   — allow IME regardless of rotation
 *   #2 makeRotateToast()             → null    — suppress "rotate phone" nag toast
 *   #3 isFlipTinyScreen()            → false   — unlock IME choice (defeat Sogou forced switch)
 *
 * Ref: refMD IME_Restrictions.md, Hook_Chain_Map.md §5
 */
object InputMethodHook {

    fun hook(param: SystemServerStartingParam) {
        if (!Config.ime) { log("InputMethodHook: DISABLED by persist.flipunlock.ime"); return }
        log("InputMethodHook: setting up")
        safeHook("InputMethodHook") {
            hookShouldShowCurrentInput(param)
            hookMakeRotateToast(param)
            hookIsFlipTinyScreen(param)
        }
        log("InputMethodHook: done")
    }

    /**
     * Hook #1: shouldShowCurrentInput(Context) → true
     *
     * Original logic:
     *   if (!isFlipDevice()) return true
     *   if (screenType != 1) return true       // inner screen
     *   if (rotation == 1 || 3) return false   // landscape BLOCKED
     *   return true                            // portrait allowed
     *
     * We force true — IME works in any orientation on outer screen.
     */
    private fun hookShouldShowCurrentInput(param: SystemServerStartingParam) {
        runCatching {
            val immClass = param.classLoader.loadClass(
                "com.android.server.inputmethod.InputMethodManagerServiceImpl")
            val method = immClass.method("shouldShowCurrentInput", Context::class.java)
            hook(method, replaceResult(true))
            log("InputMethodHook: #1 shouldShowCurrentInput → true")
        }.onFailure { log("InputMethodHook: #1 failed", it) }
    }

    /**
     * Hook #2: makeRotateToast() → null (suppress)
     *
     * Shows "当前角度不支持输入，请旋转后使用" toast when IME is blocked in landscape.
     * Since we unblock IME in #1, this toast should never appear.
     */
    private fun hookMakeRotateToast(param: SystemServerStartingParam) {
        runCatching {
            val immClass = param.classLoader.loadClass(
                "com.android.server.inputmethod.InputMethodManagerServiceImpl")
            val method = immClass.method("makeRotateToast")
            hook(method, replaceResult(null))
            log("InputMethodHook: #2 makeRotateToast → suppressed")
        }.onFailure { log("InputMethodHook: #2 failed", it) }
    }

    /**
     * Hook #3: isFlipTinyScreen() → false
     *
     * This is a SEPARATE method on InputMethodManagerServiceImpl (not MiuiConfigs).
     * SogouInputMethodSwitcher.mayChangeInputMethodLocked() calls this to decide
     * whether to auto-switch to Sogou on the outer screen.
     *
     * Returning false defeats the forced Sogou lock and allows any IME.
     * Also defeats shouldHideImeSwitcherLocked() — IME picker is shown.
     */
    private fun hookIsFlipTinyScreen(param: SystemServerStartingParam) {
        runCatching {
            val immClass = param.classLoader.loadClass(
                "com.android.server.inputmethod.InputMethodManagerServiceImpl")
            val method = immClass.method("isFlipTinyScreen")
            hook(method, replaceResult(false))
            log("InputMethodHook: #3 isFlipTinyScreen → false")
        }.onFailure { log("InputMethodHook: #3 failed", it) }
    }
}
