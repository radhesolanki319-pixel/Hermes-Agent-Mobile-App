package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.data.AppPreferences
import java.io.File

/**
 * Process-wide owner of the phone-control (Shizuku) lifecycle: one
 * [PhoneControlManager] + one [PhoneControlBridge], created lazily and
 * shared by the Settings section (toggle + permission UI) and the
 * app-start restore in MainViewModel.
 *
 * State model: [AppPreferences.phoneControlEnabled] is the user's
 * intent. The feature is ACTIVE only when the intent is on, Shizuku
 * permission is granted, the user service is bound, and the bridge is
 * listening — [start] walks that chain and stops wherever a link is
 * missing (the Settings section surfaces which one).
 */
object PhoneControlController {

    private var manager: PhoneControlManager? = null
    private var bridge: PhoneControlBridge? = null

    private fun manager(context: Context): PhoneControlManager =
        manager ?: PhoneControlManager(context.applicationContext).also { manager = it }

    private fun bridge(context: Context): PhoneControlBridge =
        bridge ?: PhoneControlBridge(
            context = context.applicationContext,
            phoneControlManager = manager(context),
            preferences = AppPreferences(context.applicationContext),
            guestTokenFile = {
                runCatching { RuntimeInstaller(context.applicationContext).installedRuntime().rootfs }
                    .getOrNull()
                    ?.let { File(it, "opt/pocket/harness-host.token") }
            },
        ).also { bridge = it }

    /** -1 Shizuku unavailable, 0 permission needed, 1 granted (see PhoneControlManager). */
    fun permissionStatus(context: Context): Int = manager(context).getPermissionStatus()

    fun requestPermission(context: Context) = manager(context).requestPermission()

    fun isEnabled(context: Context): Boolean =
        AppPreferences(context.applicationContext).phoneControlEnabled

    /** True when the service is bound and the bridge is listening. */
    fun isActive(context: Context): Boolean =
        manager?.isReady() == true && bridge?.isRunning() == true

    fun setEnabled(context: Context, enabled: Boolean, onStateChanged: () -> Unit = {}) {
        AppPreferences(context.applicationContext).phoneControlEnabled = enabled
        if (enabled) {
            start(context, onStateChanged)
        } else {
            stop(onStateChanged)
        }
    }

    /** App-start restore: rebind + restart the bridge when the user left the feature on. */
    fun startIfEnabled(context: Context) {
        if (isEnabled(context)) start(context)
    }

    private fun start(context: Context, onStateChanged: () -> Unit = {}) {
        val m = manager(context)
        if (m.getPermissionStatus() != 1) {
            // Shizuku missing or permission not granted yet: the intent
            // stays recorded and a later start (Settings revisit, next
            // app launch) retries once the link exists.
            onStateChanged()
            return
        }
        if (m.isReady()) {
            bridge(context).start()
            onStateChanged()
            return
        }
        m.bindService { bound ->
            if (bound) bridge(context).start()
            onStateChanged()
        }
    }

    private fun stop(onStateChanged: () -> Unit = {}) {
        bridge?.stop()
        manager?.unbindService()
        onStateChanged()
    }
}
