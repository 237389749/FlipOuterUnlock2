package com.example.flipunlock.hook.ime

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Sogou IME fix for outer screen — restore toolbar and clipboard visibility.
 *
 * Uses DexKit to locate the FlipScreenManager class by the string constant
 * "flip_old_outer_keyboard", then finds isFlipScreen() within it.
 *
 * Hooks:
 *   Toolbar fix: buildFunctionList + refreshFunctionList — remove items 6, 1052
 *   Clipboard fix: onCandidateChange + showFunctionOrClipboard — don't hide clipboard
 *
 * Ref: refMD Hook_Chain_Map.md §5
 */
object SogouInputHook : BaseHook() {
    override val targetPackages = listOf("com.sohu.inputmethod.sogou.xiaomi")

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.ime) { log("SogouInputHook: DISABLED by persist.flipunlock.ime"); return }
        log("SogouInputHook: loading for ${param.packageName}")
        safeHook("SogouInputHook") {
            hookToolbarFix(param)
            hookClipboardFix(param)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun findManagerClass(bridge: DexKitBridge, classLoader: ClassLoader): Class<*> {
        val found = bridge.findClass {
            matcher { usingStrings("flip_old_outer_keyboard") }
        }.singleOrNull()?.getInstance(classLoader)
        if (found == null) log("SogouFix: FlipScreenManager class not found — wrong Sogou version?")
        return found ?: error("FlipScreenManager not found")
    }

    private fun findIsFlipScreen(bridge: DexKitBridge, classLoader: ClassLoader, managerClass: Class<*>): Method {
        val found = bridge.findMethod {
            matcher {
                declaredClass(managerClass.name)
                invokeMethods { add { name = "isFlipScreen" } }
                returnType = "boolean"
                paramCount = 0
            }
        }.firstNotNullOfOrNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
        if (found == null) log("SogouFix: isFlipScreen not found in ${managerClass.name}")
        return found ?: error("isFlipScreen not found")
    }

    // ── Toolbar fix ────────────────────────────────────────────────────

    private fun hookToolbarFix(param: PackageReadyParam) {
        createDexKitBridge(param.classLoader).use { bridge ->
            val managerClass = findManagerClass(bridge, param.classLoader)
            val isFlipScreen = findIsFlipScreen(bridge, param.classLoader, managerClass)
            // isFlipScreen IS m44475j() on FlipScreenManager — all callers (interface
            // and direct) go through this single method.
            val fakeFalse = hookScope(isFlipScreen) { false }
            val fakeTrue  = hookScope(isFlipScreen) { true }

            // buildFunctionList (m37421q): calls getFlipOrderList + isFlipScreen
            //   Fake false → isFlipScreen()=false → skip getFlipOrderList → use default list
            val buildFunctionList = bridge.findMethod {
                matcher {
                    invokeMethods {
                        add { name = "getFlipOrderList" }
                        add { name = "isFlipScreen" }
                    }
                }
            }.singleOrNull()?.getMethodInstance(param.classLoader)
                ?: error("buildFunctionList not found")

            hook(buildFunctionList) { chain ->
                val result = fakeFalse.run { chain.proceed() }
                // fakeFalse makes isFlipScreen()=false → skip getFlipOrderList → default list
                // Always remove items 6, 1052 from the result (they're in the default list)
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val list = result as? ArrayList<Any>
                    if (!list.isNullOrEmpty()) {
                        val idField = findItemIdField(list[0].javaClass)
                        if (idField != null) {
                            list.removeIf { item -> idField.get(item) as? Int in listOf(6, 1052) }
                        }
                    }
                }.onFailure { log("SogouFix: hookToolbarFix after failed", it) }
                result
            }

            // refreshFunctionList (m37390G): NEW VERSION calls m44475j() directly
            //   (not isFlipScreen() interface method).
            //   Logic: if (!m7819t8() || m44475j()) → m37387D() [default list]
            //          else → m37391H() [user-customized list — may contain items 6, 1052]
            //   Fake TRUE → m44475j()=true → always takes default list path.
            val refreshFunctionList = bridge.findMethod {
                matcher {
                    declaredClass(buildFunctionList.declaringClass.name)
                    invokeMethods { add { name = "isUpdateFlipImeFunction" } }
                }
            }.singleOrNull()?.getMethodInstance(param.classLoader)
                ?: error("refreshFunctionList not found")

            hook(refreshFunctionList) { chain -> fakeTrue.run { chain.proceed() } }
            log("SogouFix: toolbar fix hooked (buildFunctionList fake=false, refreshFunctionList fake=true)")
        }
    }

    /**
     * Find the int field that holds the toolbar item ID.
     * In the new version this is C12755f.a.f55326f (renamed from "f").
     * Falls back to the first int field if "f" is not found.
     */
    private fun findItemIdField(clazz: Class<*>): java.lang.reflect.Field? {
        return runCatching {
            clazz.getDeclaredField("f").also { it.isAccessible = true }
        }.getOrElse {
            // Fallback: find any int field (the item ID is the only int in the toolbar item class)
            clazz.declaredFields.firstOrNull { f ->
                f.type == Int::class.javaPrimitiveType && !Modifier.isStatic(f.modifiers)
            }?.also {
                it.isAccessible = true
                log("SogouFix: field 'f' not found, using fallback field '${it.name}'")
            }
        }
    }

    // ── Clipboard fix ──────────────────────────────────────────────────

    private fun hookClipboardFix(param: PackageReadyParam) {
        createDexKitBridge(param.classLoader).use { bridge ->
            val managerClass = findManagerClass(bridge, param.classLoader)
            val isFlipScreen = findIsFlipScreen(bridge, param.classLoader, managerClass)

            // onCandidateChange: string "ClipboardToCandsController onCandidateChange" + calls isFlipScreen
            val onCandidateChange = bridge.findMethod {
                matcher {
                    usingStrings("ClipboardToCandsController onCandidateChange")
                    invokeMethods { add { name = "isFlipScreen" } }
                }
            }.singleOrNull()?.getMethodInstance(param.classLoader)
                ?: error("onCandidateChange not found")

            val containerClass = param.classLoader.loadClass(
                "com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer"
            )

            val fakeClipboard = hookScope(isFlipScreen) { false }
                .stopOn(containerClass.method("showClipboardFirstCandidate"))
            hook(onCandidateChange) { chain ->
                fakeClipboard.run { chain.proceed() }
            }

            // showFunctionOrClipboard: void, 0 params, calls both show methods
            val showFunctionOrClipboard = bridge.findMethod {
                matcher {
                    returnType = "void"
                    paramCount = 0
                    invokeMethods {
                        add { name = "showIMEFunctionOrFirstClipboardView" }
                        add { name = "showIMEFunctionCandidateView" }
                    }
                }
            }.mapNotNull { runCatching { it.getMethodInstance(param.classLoader) }.getOrNull() }
                .firstOrNull {
                    it.declaringClass.name.startsWith("com.sohu.inputmethod.main.manager.") &&
                        Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers)
                } ?: error("showFunctionOrClipboard not found")

            val fakeFunction = hookScope(isFlipScreen) { false }
                .stopOn(containerClass.method("showIMEFunctionOrFirstClipboardView"))
            hook(showFunctionOrClipboard) { chain ->
                fakeFunction.run { chain.proceed() }
            }

            log("SogouFix: clipboard fix hooked")
        }
    }
}
