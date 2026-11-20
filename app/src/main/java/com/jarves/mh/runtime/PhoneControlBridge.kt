package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import com.jarves.mh.data.AppPreferences
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * Localhost bridge server for phone control (Shizuku).
 * Listens on 127.0.0.1:37123 for commands from the guest Ubuntu.
 * Protocol:
 *   Client connects
 *   Line 1: auth token (must match the stored token)
 *   Line 2: command to execute
 *   Response: command output (stdout+stderr)
 *   Connection closed after response
 *
 * The guest reaches this listener because PRoot guests share the
 * device's loopback. Started when the feature is enabled (and the
 * Shizuku service is bound), stopped when disabled.
 *
 * The token is random per install and lives in [AppPreferences] — the
 * single source of truth. The guest-side copy at
 * /opt/pocket/harness-host.token is seeded by the installer and
 * refreshed here on start through [guestTokenFile], which resolves the
 * file inside the guest rootfs (the app process itself has no
 * /opt/pocket path).
 */
class PhoneControlBridge(
    private val context: Context,
    private val phoneControlManager: PhoneControlManager,
    private val preferences: AppPreferences,
    private val guestTokenFile: () -> File?,
) {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor()

    /** Start the bridge server (called when the feature is enabled). */
    fun start() {
        if (serverSocket != null) return // Already running
        if (!phoneControlManager.isReady()) {
            Log.w(TAG, "PhoneControlManager not ready; bridge not started")
            return
        }

        // Refresh the guest's token file so harness-host can authenticate.
        writeTokenFile()

        serverSocket = ServerSocket(PORT, 0, InetAddress.getByName("127.0.0.1"))
        serverThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleClient(socket) }
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted) {
                        Log.e(TAG, "Accept error", e)
                    }
                    break
                }
            }
        }, "PhoneControlBridge").apply { isDaemon = true; start() }
        Log.i(TAG, "Started on 127.0.0.1:$PORT")
    }

    /** Write the auth token into the guest rootfs token file. */
    private fun writeTokenFile() {
        try {
            val tokenFile = guestTokenFile() ?: return
            tokenFile.parentFile?.mkdirs()
            tokenFile.writeText(preferences.ensurePhoneControlToken())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write token file", e)
        }
    }

    /** Stop the bridge server (called when the feature is disabled). */
    fun stop() {
        serverThread?.interrupt()
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        serverThread = null
        executor.shutdownNow()
        Log.i(TAG, "Stopped")
    }

    /** Check if the bridge is running. */
    fun isRunning(): Boolean = serverSocket != null && !serverSocket!!.isClosed

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.setSoTimeout(30_000) // 30s total timeout
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val writer = OutputStreamWriter(s.getOutputStream())

            // Read auth token (first line)
            val receivedToken = reader.readLine()?.trim() ?: return
            if (receivedToken != preferences.ensurePhoneControlToken()) {
                writer.write("AUTH_FAILED\n")
                writer.flush()
                return
            }

            // Read command (second line)
            val command = reader.readLine()?.trim() ?: return

            // Execute via the Shizuku service. This handler already runs
            // on a background executor thread, so blocking here is fine.
            val output = runBlocking { phoneControlManager.execOnHost(command) }

            // Send response (capped)
            val trimmedOutput = if (output.length > MAX_OUTPUT_CHARS) {
                output.take(MAX_OUTPUT_CHARS) + "\n[output truncated]"
            } else {
                output
            }

            writer.write(trimmedOutput)
            writer.write('\n')
            writer.flush()
        }
    }

    companion object {
        private const val TAG = "PhoneControlBridge"
        private const val PORT = 37123
        private const val MAX_OUTPUT_CHARS = 128_000
    }
}
