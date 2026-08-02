package com.example.flipunlock.hook.system_server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.InputStream

/**
 * Remove the outer-screen app allowlist restriction by enrolling every
 * installed package into the continuity "allowstart" list.
 *
 * Logic chain (refMD: FoldState_Device_Identity.md §6):
 *
 *   dumpsys window -setForceDisplayCompatMode <pkg> allowstart
 *     → ContinuityPolicyService.handleSet()
 *       → LOCAL_POLICY_BY_COMMAND.put(pkg, "allowstart")
 *       → LOCAL_COMMAND_ALLOW_START_SET.add(pkg)
 *
 *   InterceptActivityController.isInterceptListUnCheckFold():
 *     step 1 (HIGHEST priority): LOCAL_POLICY_BY_COMMAND has "allowstart"
 *       → return false (allow launch), short-circuiting ALL later checks
 *         (interceptlist, manifest property, cloud block lists)
 *
 * Because "allowstart" is checked first and short-circuits everything,
 * enrolling all packages effectively dissolves the entire allow/block
 * list mechanism — independent of the isInterceptListUnCheckFold gate hook.
 *
 * This hook runs in system_server and:
 *   1. Whitelists all currently installed apps on boot
 *   2. Re-whitelists on package install/remove/replace
 *
 * Process: system_server
 * Source: ContinuityPolicyService shell handler (registered at boot phase 600)
 */
object AppWhitelist {

    @Volatile
    private var isUpdating = false

    fun hook(param: SystemServerStartingParam) {
        log("AppWhitelist: setting up")
        safeHook("AppWhitelist") {
            // System context via ActivityThread.systemMain() (static)
            val activityThreadClass = param.classLoader.loadClass("android.app.ActivityThread")
            val systemContext = activityThreadClass.method("systemMain").invoke(null) as? Context
                ?: run { log("AppWhitelist: systemMain returned null"); return@safeHook }

            updateWhitelist(systemContext)

            // Re-whitelist when packages change
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            systemContext.registerReceiver(PackageChangeReceiver(systemContext), filter)
            log("AppWhitelist: receiver registered, initial whitelist issued")
        }
    }

    private fun updateWhitelist(context: Context) {
        if (isUpdating) return
        isUpdating = true
        Thread {
            try {
                val apps = context.packageManager.getInstalledApplications(0)
                val allApps = apps.joinToString(":") { it.packageName }
                if (allApps.isEmpty()) return@Thread

                // ServiceManager.getService("window") (static)
                val smClass = Class.forName("android.os.ServiceManager")
                val windowBinder = smClass.method("getService", String::class.java)
                    .invoke(null, "window") as? IBinder
                    ?: run { log("AppWhitelist: window binder null"); return@Thread }

                val dumpMethod = windowBinder.javaClass.getMethod(
                    "dump", java.io.FileDescriptor::class.java, Array<String>::class.java)

                val pipe = ParcelFileDescriptor.createPipe()
                val input: InputStream = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
                try {
                    dumpMethod.invoke(
                        windowBinder,
                        pipe[1].fileDescriptor,
                        arrayOf("-setForceDisplayCompatMode", allApps, "allowstart"))
                    pipe[1].close()
                } catch (_: Exception) {
                    runCatching { pipe[1].close() }
                }

                // Drain output with a deadline so we never block forever
                val buffer = ByteArray(1024)
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline && input.read(buffer) != -1) { /* drain */ }
                input.close()

                log("AppWhitelist: whitelisted ${apps.size} apps")
            } catch (e: Exception) {
                log("AppWhitelist: update failed", e)
            } finally {
                isUpdating = false
            }
        }.start()
    }

    private class PackageChangeReceiver(private val ctx: Context) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Thread {
                runCatching {
                    Thread.sleep(500) // debounce rapid install/uninstall bursts
                    updateWhitelist(ctx)
                }
            }.start()
        }
    }
}
