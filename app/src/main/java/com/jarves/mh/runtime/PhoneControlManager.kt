package com.jarves.mh.runtime

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.jarves.mh.IPhoneControlService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * App-side manager for the Shizuku phone-control feature.
 *
 * Written against the real Shizuku API 13.1.5 surface (package
 * `rikka.shizuku`): permission is an Int grant state, requests take a
 * request code, and user services bind through
 * [Shizuku.bindUserService] with a [Shizuku.UserServiceArgs] — never
 * through the Android service manager.
 *
 * Binds to [PhoneControlService] (running in Shizuku's privileged
 * process) once permission is granted, and exposes
 * [execOnHost] for the localhost bridge.
 */
class PhoneControlManager(private val context: Context) {

    private var service: IPhoneControlService? = null
    private var connection: ServiceConnection? = null
    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bindJob: Job? = null

    /**
     * Current Shizuku permission status.
     * -1: Shizuku binder unavailable (not installed, or installed but not running)
     * 0: Permission not granted yet (Shizuku is running, the app must ask)
     * 1: Permission granted (ready to bind)
     */
    fun getPermissionStatus(): Int {
        if (!Shizuku.pingBinder()) return -1
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) 1 else 0
    }

    /** Ask Shizuku for permission; the answer arrives asynchronously. */
    fun requestPermission() {
        if (!Shizuku.pingBinder()) return
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /**
     * Bind to the PhoneControlService via Shizuku.
     * Must be called after permission is granted.
     */
    fun bindService(onResult: (Boolean) -> Unit) {
        if (getPermissionStatus() != 1) {
            onResult(false)
            return
        }

        bindJob?.cancel()
        bindJob = scope.launch {
            val deferred = CompletableDeferred<Boolean>()
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = IPhoneControlService.Stub.asInterface(binder)
                    Log.d(TAG, "Service connected")
                    deferred.complete(true)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    service = null
                    Log.d(TAG, "Service disconnected")
                    deferred.complete(false)
                }

                override fun onBindingDied(name: ComponentName) {
                    service = null
                    Log.w(TAG, "Binding died")
                    deferred.complete(false)
                }
            }
            connection = conn
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, PhoneControlService::class.java.name),
            )
                .daemon(false)
                .debuggable(false)
                .version(SERVICE_VERSION)
            serviceArgs = args
            try {
                Shizuku.bindUserService(args, conn)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind service", e)
                deferred.complete(false)
            }

            val result = deferred.await()
            onResult(result)
        }
    }

    /** Unbind from the service. */
    fun unbindService() {
        bindJob?.cancel()
        val args = serviceArgs
        val conn = connection
        if (args != null && conn != null) {
            try {
                Shizuku.unbindUserService(args, conn, true)
            } catch (e: Exception) {
                // Ignore: the binding may already be gone.
            }
        }
        connection = null
        serviceArgs = null
        service = null
    }

    /**
     * Execute a command on the host Android system via Shizuku.
     * Returns combined stdout+stderr (capped at 64KB by the service).
     */
    suspend fun execOnHost(command: String): String {
        val current = service
            ?: return "Error: Phone control service not bound. Is Shizuku permission granted?"
        return try {
            current.exec(command)
        } catch (e: RemoteException) {
            "Error: IPC failed - ${e.message}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** Check if the service is currently bound and ready. */
    fun isReady(): Boolean = service != null

    /** Clean up resources. */
    fun destroy() {
        unbindService()
        scope.cancel()
    }

    companion object {
        private const val TAG = "PhoneControlManager"
        private const val REQUEST_CODE = 41001

        /** Bump when PhoneControlService's behaviour changes incompatibly. */
        private const val SERVICE_VERSION = 1
    }
}
