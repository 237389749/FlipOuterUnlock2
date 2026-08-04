package com.example.flipunlock.hook.systemui

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Fix control center on the outer screen: keep COMPACT style (independent tile list)
 * but enable the edit button and fix device center dimensions.
 *
 * On flip devices, the control center plugin (miui.systemui.plugin) applies a
 * COMPACT style when isTinyScreen is true. COMPACT has its own independent tile
 * list (CompactQSListController) — but the edit button is hidden in COMPACT mode.
 *
 * Previous approach (COMPACT→VERTICAL) caused outer screen to share inner screen's
 * tile list, making independent editing impossible.
 *
 * Fix (plugin hooks):
 *   Hook #1: EditButtonController.available() → true in COMPACT (show edit button)
 *   Hook #2: QSTileItemView.onFinishInflate() → restore long-press
 *   Hook #3-6: Device center dimension/mode/list fixes
 *
 * Ported from MixFlipMod's SystemUIHook.hookControlCenter, revised approach.
 */
object ControlCenterHook : BaseHook() {

    // "android" is required because LSPosed v2.0.1 only fires onPackageReady("android")
    // in the systemui process — onPackageReady("com.android.systemui") is never called.
    override val targetPackages = listOf("android", "com.android.systemui")

    private val controlCenterComponents = setOf(
        ComponentName("miui.systemui.plugin", "miui.systemui.controlcenter.MiuiControlCenter"),
        ComponentName("miui.systemui.plugin", "miui.systemui.quicksettings.LocalMiuiQSTilePlugin"),
    )

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.uiControlCenter) {
            log("ControlCenterHook: DISABLED by persist.flipunlock.ui.controlcenter")
            return
        }
        // Process guard: only install in SystemUI
        if (param.packageName == "android") {
            val proc = currentProcessName()
            if (proc != "com.android.systemui") {
                log("ControlCenterHook: skip, process=$proc")
                return
            }
            log("ControlCenterHook: pkg=android, process=$proc — installing hooks")
        } else {
            log("ControlCenterHook: setupHooks pkg=${param.packageName}")
        }

        safeHook("ControlCenterHook") {
            hookPluginFactory(param)
        }
    }

    // ── Plugin factory interception ─────────────────────────────────────
    //
    // PluginInstance.PluginFactory.createPluginContext() creates a ContextWrapper
    // whose ClassLoader has access to the plugin APK classes. We intercept this
    // to hook plugin-internal classes when control center components are loaded.

    private fun hookPluginFactory(param: PackageReadyParam) {
        val factoryClass = param.classLoader.loadClass(
            "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory")

        hook(factoryClass.method("createPluginContext"), object : Hooker {
            private var isHooked = false

            override fun intercept(chain: Chain): Any? {
                val result = chain.proceed()
                val mComponentName = chain.thisObject?.getField("mComponentName") as? ComponentName
                    ?: return result
                if (isHooked) return result
                if (mComponentName !in controlCenterComponents) return result

                val pluginLoader = (result as? ContextWrapper)?.classLoader ?: return result
                isHooked = true
                log("ControlCenterHook: plugin loaded, installing internal hooks")

                runCatching {
                    installAllPluginHooks(pluginLoader)
                }.onFailure { log("ControlCenterHook: plugin init failed", it) }
                return result
            }
        })
        log("ControlCenterHook: plugin factory hook installed")
    }

    // ── All plugin-internal hooks (shared isTinyScreen tracking) ─────────

    private fun installAllPluginHooks(pluginLoader: ClassLoader) {
        val styleClass = pluginLoader.loadClass(
            "miui.systemui.controlcenter.panel.main.MainPanelController\$Style")
        val compactStyle = styleClass.field("COMPACT").get(null)

        // Track isTinyScreen via set_style
        var isTinyScreen = false
        val panelClass = pluginLoader.loadClass(
            "miui.systemui.controlcenter.panel.main.MainPanelStyleController")

        hook(panelClass.method("set_style", styleClass)) { styleChain ->
            isTinyScreen = styleChain.args[0] == compactStyle
            styleChain.proceed()
        }

        // ── 1. Edit button: enable in COMPACT mode ─────────────────────
        //
        // EditButtonController.available() returns false when style == COMPACT.
        // We hook it to return true so the edit button shows on the outer screen.
        // This allows editing the COMPACT tile list independently.

        runCatching {
            val editBtnClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.panel.main.p113qs.EditButtonController")
            hook(editBtnClass.method("available", Boolean::class.java), Hooker { chain ->
                if (isTinyScreen) {
                    // Force available in COMPACT mode — but still respect
                    // superSaveMode and non-NORMAL mode checks
                    true
                } else {
                    chain.proceed()
                }
            })
            log("ControlCenterHook: edit button enabled in COMPACT mode")
        }.onFailure { log("ControlCenterHook: edit button hook failed", it) }

        // ── 2. QS tile long-press restore ──────────────────────────────

        runCatching {
            val tileClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.p114qs.tileview.QSTileItemView")
            hook(tileClass.method("onFinishInflate"), after { tileChain, tileResult ->
                (tileChain.thisObject as? FrameLayout)?.setOnLongClickListener { v ->
                    tileChain.thisObject
                        ?.getField("longClickAction")
                        ?.let { it.callMethod("invoke", v) as? Boolean }
                        ?: false
                }
                tileResult
            })
            log("ControlCenterHook: QS tile long-press restored")
        }.onFailure { log("ControlCenterHook: tile long-press failed", it) }

        // ── 3. Device center: dimension fix ────────────────────────────

        runCatching {
            val rdimenClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.R\$dimen")
            val targetId = rdimenClass.field("device_center_device_item_width").getInt(null)
            hook(Resources::class.java.method("getDimensionPixelSize", Int::class.java),
                after { dimenChain, dimenResult ->
                    if (isTinyScreen && dimenChain.args[0] == targetId) 245
                    else dimenResult
                })
        }.onFailure { log("ControlCenterHook: dimension fix failed", it) }

        // ── 4. Device center: ViewHolder width override ────────────────

        runCatching {
            val adapterClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceCenterCardController\$_adapter\$1")
            hook(adapterClass.method("onCreateViewHolder",
                ViewGroup::class.java, Int::class.java),
                after { _, holderResult ->
                    (holderResult?.getField("itemView") as? View)
                        ?.takeIf { isTinyScreen && it.layoutParams.width != -1 }
                        ?.let { it.layoutParams.width = 245 }
                    holderResult
                })
        }.onFailure { log("ControlCenterHook: ViewHolder width override failed", it) }

        // ── 5. Device center: getMode() override ───────────────────────

        runCatching {
            val modeClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryViewHolder\$Mode")
            val modeCollapsed = modeClass.field("MODE_COLLAPSED").get(null)
            val mode1row = modeClass.field("MODE_1_ROW").get(null)
            val mode2row = modeClass.field("MODE_2_ROWS").get(null)

            val cardCtrlClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceCenterCardController")
            hook(cardCtrlClass.method("getMode"), Hooker { modeChain ->
                if (!isTinyScreen) return@Hooker modeChain.proceed()
                val size = (modeChain.thisObject?.getField("deviceItems") as? ArrayList<*>)?.size
                    ?: return@Hooker modeChain.proceed()
                when {
                    size == 1 -> modeCollapsed
                    size < 4 -> mode1row
                    else -> mode2row
                }
            })
        }.onFailure { log("ControlCenterHook: getMode override failed", it) }

        // ── 6. Device center: limit device list to 5 ───────────────────

        runCatching {
            val deviceCtrlClass = pluginLoader.loadClass(
                "miui.systemui.controlcenter.devicecenter.DeviceCenterController")
            hook(deviceCtrlClass.method("handleDeviceListUpdate", Boolean::class.java),
                Hooker { deviceChain ->
                    if (!isTinyScreen) return@Hooker deviceChain.proceed()
                    val deviceList = deviceChain.thisObject?.getField("deviceList") as? ArrayList<*>
                        ?: return@Hooker deviceChain.proceed()
                    if (deviceList.size <= 5) return@Hooker deviceChain.proceed()
                    deviceChain.thisObject?.setField("deviceList",
                        deviceList.subList(0, 5).toList())
                    runWithCleanup({ deviceChain.thisObject?.setField("deviceList", deviceList) }) {
                        deviceChain.proceed()
                    }
                })
        }.onFailure { log("ControlCenterHook: device list limit failed", it) }

        log("ControlCenterHook: device center hooks installed")
    }

    private fun currentProcessName(): String? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentProcessName").invoke(null) as? String
    }.getOrNull()
}
