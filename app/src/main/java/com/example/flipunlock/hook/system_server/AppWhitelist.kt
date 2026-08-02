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
 * TIMING (critical — a naive eager version crashed system_server):
 *   onSystemServerStarting fires BEFORE system services exist. Calling
 *   ActivityThread.systemMain() there creates+attaches a SECOND ActivityThread
 *   and corrupts system_server bootstrap → bootloop → LSPosed disables modules.
 *
 *   Therefore ALL work is deferred to a background thread that:
 *     1. waits for ActivityThread.currentActivityThread() (the EXISTING thread,
 *        obtained without creating a new one) to get the system context
 *     2. waits for sys.boot_completed=1 — the -setForceDisplayCompatMode shell
 *        handler is only registered at boot phase 600 (BOOT_COMPLETED), so the
 *        dump command is a no-op before that
 *     3. only then issues the dump command and registers the package receiver
 *
 * Process: system_server
 * Source: ContinuityPolicyService shell handler (registered at boot phase 600)
 */
object AppWhitelist {

    @Volatile
    private var isUpdating = false

    private const val WAIT_DEADLINE_MS = 180_000L  // give up if boot takes >3 min
    private const val POLL_INTERVAL_MS = 2_000L

    fun hook(param: SystemServerStartingParam) {
        log("AppWhitelist: armed (deferred until boot completes)")
        // Do NOTHING synchronously here — system_server is mid-bootstrap.
        Thread {
            runCatching { deferredInit() }
                .onFailure { log("AppWhitelist: deferred init failed", it) }
        }.start()
    }

    private fun deferredInit() {
        val context = waitForSystemContext() ?: run {
            log("AppWhitelist: gave up waiting for system context")
            return
        }
        waitForBootCompleted()
        updateWhitelist(context)
        registerPackageReceiver(context)
        log("AppWhitelist: ready")
    }

    /**
     * Poll until the EXISTING ActivityThread is available and return its
     * system context. Never calls systemMain() (which would create a new
     * ActivityThread and break system_server bootstrap).
     */
    private fun waitForSystemContext(): Context? {
        val atClass = Class.forName("android.app.ActivityThread")
        val currentAT = atClass.method("currentActivityThread")
        val deadline = System.currentTimeMillis() + WAIT_DEADLINE_MS
        while (System.currentTimeMillis() < deadline) {
            val ctx = runCatching {
                val at = currentAT.invoke(null) ?: return@runCatching null
                at.callMethod("getSystemContext") as? Context
            }.getOrNull()
            if (ctx != null) return ctx
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return null
    }

    /** Block until sys.boot_completed=1 (shell handler registered at phase 600). */
    private fun waitForBootCompleted() {
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.method("get", String::class.java, String::class.java)
        val deadline = System.currentTimeMillis() + WAIT_DEADLINE_MS
        while (System.currentTimeMillis() < deadline) {
            val done = runCatching {
                get.invoke(null, "sys.boot_completed", "0") as? String == "1"
            }.getOrDefault(false)
            if (done) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log("AppWhitelist: boot_completed never observed, proceeding anyway")
    }

    private fun updateWhitelist(context: Context) {
        if (isUpdating) return
        isUpdating = true
        Thread {
            try {
                val apps = context.packageManager.getInstalledApplications(0)
                val allApps = apps.joinToString(":") { it.packageName }
                if (allApps.isEmpty()) return@Thread

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

    private fun registerPackageReceiver(context: Context) {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            // Android 14+ requires an explicit export flag.
            context.registerReceiver(
                PackageChangeReceiver(context), filter, Context.RECEIVER_EXPORTED)
            log("AppWhitelist: package receiver registered")
        }.onFailure { log("AppWhitelist: registerReceiver failed", it) }
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
