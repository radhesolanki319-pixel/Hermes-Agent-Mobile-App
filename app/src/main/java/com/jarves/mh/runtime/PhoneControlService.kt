package com.jarves.mh.runtime

import com.jarves.mh.IPhoneControlService

/**
 * Shizuku user service that executes commands on the host Android
 * system. Shizuku instantiates this class directly inside its own
 * privileged process (shell/root) and the instance itself is the
 * binder handed back to the app — so it extends the AIDL [Stub]
 * rather than android.app.Service, and it is NOT declared in the
 * manifest (user services are addressed by component name through
 * Shizuku's bindUserService, not by the Android service manager).
 *
 * exec() runs the command via `sh -c` with stderr merged into stdout
 * and returns the combined output capped at ~64KB.
 */
class PhoneControlService : IPhoneControlService.Stub() {

    override fun exec(command: String): String = runCommand(command)

    /** Called reflectively by Shizuku when the binding is torn down. */
    fun destroy() {
        // Nothing to release: each exec is a short-lived child process.
    }

    private fun runCommand(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val maxBytes = 64 * 1024 // 64KB cap
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.append(buffer, 0, count)
                    if (output.length >= maxBytes) break
                }
            }
            if (!process.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                output.append("\n[command timed out after 25s]")
            }
            if (output.length > maxBytes) {
                output.delete(maxBytes, output.length)
                output.append("\n[output truncated at 64KB]")
            }
            output.toString().trim()
        } catch (e: Exception) {
            "Error executing command: ${e.message}"
        }
    }
}
