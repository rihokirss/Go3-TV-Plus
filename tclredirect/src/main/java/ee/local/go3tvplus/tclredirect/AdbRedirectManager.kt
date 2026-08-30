package ee.local.go3tvplus.tclredirect

import android.content.Context
import android.util.Base64
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class AdbRedirectManager(context: Context) {
    private val storageContext = context.createDeviceProtectedStorageContext()
    private val preferences = storageContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val profile = RedirectProfile.current()
    private val privateKey = File(storageContext.filesDir, "tcl_redirect_adb_private.key")
    private val publicKey = File(storageContext.filesDir, "tcl_redirect_adb_public.key")

    val isConfigured: Boolean
        get() = preferences.getBoolean(KEY_CONFIGURED, false)

    @Synchronized
    fun ensureRedirect(authorizationTimeoutSeconds: Long): String {
        val result = withAdb(authorizationTimeoutSeconds) { connection ->
            runShell(connection, bootstrapCommand())
        }
        preferences.edit().putBoolean(KEY_CONFIGURED, true).apply()
        return result.ifBlank { "READY" }
    }

    @Synchronized
    fun restoreOriginalButton(authorizationTimeoutSeconds: Long): String {
        val result = withAdb(authorizationTimeoutSeconds) { connection ->
            runShell(connection, stopCommand(restoreCompetingApp = true))
        }
        preferences.edit().putBoolean(KEY_CONFIGURED, false).apply()
        return result.ifBlank { "RESTORED" }
    }

    private fun <T> withAdb(timeoutSeconds: Long, block: (AdbConnection) -> T): T {
        val socket = Socket()
        socket.connect(InetSocketAddress(ADB_HOST, ADB_PORT), SOCKET_CONNECT_TIMEOUT_MS)
        socket.soTimeout = SOCKET_READ_TIMEOUT_MS

        val connection = AdbConnection.create(socket, loadOrCreateCrypto())
        try {
            if (!connection.connect(timeoutSeconds, TimeUnit.SECONDS, false)) {
                throw IOException("ADB authorization timed out")
            }
            return block(connection)
        } finally {
            connection.close()
        }
    }

    private fun loadOrCreateCrypto(): AdbCrypto {
        val encoder = object : AdbBase64 {
            override fun encodeToString(data: ByteArray): String =
                Base64.encodeToString(data, Base64.NO_WRAP)
        }

        if (privateKey.isFile && publicKey.isFile) {
            return AdbCrypto.loadAdbKeyPair(encoder, privateKey, publicKey)
        }

        val crypto = AdbCrypto.generateAdbKeyPair(encoder)
        crypto.saveAdbKeyPair(privateKey, publicKey)
        return crypto
    }

    private fun runShell(connection: AdbConnection, command: String): String {
        val stream = connection.open("shell:$command")
        val output = StringBuilder()
        try {
            while (!stream.isClosed) {
                try {
                    output.append(String(stream.read(), StandardCharsets.UTF_8))
                } catch (_: IOException) {
                    break
                }
            }
        } finally {
            stream.close()
        }
        return output.toString().trim()
    }

    private fun bootstrapCommand(): String {
        if (!profile.usesTclAutoStart) {
            return buildString {
                append(disableCompetingAppCommand())
                append(enableSonyAccessibilityCommand())
                append("echo STARTED")
            }
        }
        val encodedScript = Base64.encodeToString(
            listenerScript().toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        return buildString {
            if (profile.usesTclAutoStart) {
                append("cmd appops set $HELPER_PACKAGE APP_AUTO_START allow >/dev/null 2>&1; ")
                append(stopLegacyTclListenerCommand())
            }
            append("if [ -r '$REMOTE_PID' ]; then ")
            append("pid=\$(cat '$REMOTE_PID'); ")
            append("if kill -0 \"\$pid\" >/dev/null 2>&1; then ")
            append(disableCompetingAppCommand())
            append("echo ALREADY_RUNNING; exit 0; fi; fi; ")
            append(stopCommand(restoreCompetingApp = false, printResult = false))
            append("printf '%s' '$encodedScript' | base64 -d > '$REMOTE_SCRIPT'; ")
            append("chmod 755 '$REMOTE_SCRIPT'; ")
            append(disableCompetingAppCommand())
            append("nohup setsid '$REMOTE_SCRIPT' </dev/null >/dev/null 2>&1 & ")
            append("sleep 1; ")
            append("if [ -r '$REMOTE_PID' ] && kill -0 \"\$(cat '$REMOTE_PID')\" >/dev/null 2>&1; ")
            append("then echo STARTED; else echo FAILED; exit 1; fi")
        }
    }

    private fun stopCommand(
        restoreCompetingApp: Boolean,
        printResult: Boolean = true,
    ): String = buildString {
        if (profile.usesTclAutoStart) append(stopLegacyTclListenerCommand())
        append("if [ -r '$REMOTE_PID' ]; then ")
        append("pid=\$(cat '$REMOTE_PID'); ")
        append("pgid=\$(ps -A -o PID,PGID | awk -v target=\"\$pid\" '")
        append("\$1 == target { print \$2; exit }'); ")
        append("if [ -n \"\$pgid\" ]; then /system/bin/kill -TERM \"-\$pgid\" >/dev/null 2>&1 || true; ")
        append("else /system/bin/kill \"\$pid\" >/dev/null 2>&1 || true; fi; ")
        append("i=0; while kill -0 \"\$pid\" >/dev/null 2>&1 && [ \"\$i\" -lt 20 ]; ")
        append("do sleep 0.1; i=\$((i + 1)); done; rm -f '$REMOTE_PID'; fi; ")
        if (restoreCompetingApp) {
            if (!profile.usesTclAutoStart) append(disableSonyAccessibilityCommand())
            append("pm enable --user 0 ${profile.competingPackage} >/dev/null 2>&1; ")
            if (profile.usesTclAutoStart) {
                append("cmd appops set $HELPER_PACKAGE APP_AUTO_START ignore >/dev/null 2>&1; ")
            }
        }
        if (printResult) append("echo RESTORED")
    }

    private fun listenerScript(): String {
        val dollar = '$'
        val keyMatch = if (profile.symbolicKeyCode != null) {
            "*EV_KEY*${profile.rawKeyCode}*DOWN*|*EV_KEY*${profile.symbolicKeyCode}*DOWN*"
        } else {
            "*EV_KEY*${profile.rawKeyCode}*DOWN*"
        }
        return """
            #!/system/bin/sh
            echo "${dollar}${dollar}" > "$REMOTE_PID"
            trap 'rm -f "$REMOTE_PID"' EXIT
            while true; do
              getevent -lt 2>/dev/null | while IFS= read -r line; do
                case "${dollar}line" in
                  $keyMatch)
                    echo "${dollar}(date +%FT%T) ${profile.sourceButtonName} button" >> "$REMOTE_LOG"
                    am start --activity-clear-top --activity-single-top -n "$GO3_COMPONENT" >/dev/null 2>&1
                    ;;
                esac
              done
              sleep 1
            done
        """.trimIndent() + "\n"
    }

    private fun disableCompetingAppCommand(): String =
        "pm disable-user --user 0 ${profile.competingPackage} >/dev/null 2>&1; "

    private fun stopLegacyTclListenerCommand(): String = buildString {
        append("if [ -r '$LEGACY_TCL_REMOTE_PID' ]; then ")
        append("legacy_pid=\$(cat '$LEGACY_TCL_REMOTE_PID'); ")
        append("legacy_pgid=\$(ps -A -o PID,PGID | awk -v target=\"\$legacy_pid\" '")
        append("\$1 == target { print \$2; exit }'); ")
        append("if [ -n \"\$legacy_pgid\" ]; then ")
        append("/system/bin/kill -TERM \"-\$legacy_pgid\" >/dev/null 2>&1 || true; ")
        append("else /system/bin/kill \"\$legacy_pid\" >/dev/null 2>&1 || true; fi; ")
        append("rm -f '$LEGACY_TCL_REMOTE_PID' '$LEGACY_TCL_REMOTE_SCRIPT'; fi; ")
    }

    private fun enableSonyAccessibilityCommand(): String {
        val dollar = '$'
        return buildString {
            append("current=${dollar}(settings get secure enabled_accessibility_services); ")
            append("case \"${dollar}current\" in ")
            append("null|'') next='$SONY_ACCESSIBILITY_SERVICE' ;; ")
            append("*'$SONY_ACCESSIBILITY_SERVICE'*) next=\"${dollar}current\" ;; ")
            append("*) next='$SONY_ACCESSIBILITY_SERVICE':\"${dollar}current\" ;; esac; ")
            append("settings put secure enabled_accessibility_services \"${dollar}next\"; ")
            append("settings put secure accessibility_enabled 1; ")
        }
    }

    private fun disableSonyAccessibilityCommand(): String {
        val dollar = '$'
        return buildString {
            append("current=${dollar}(settings get secure enabled_accessibility_services); ")
            append("next=${dollar}(printf '%s' \"${dollar}current\" | sed ")
            append("'s#^$SONY_ACCESSIBILITY_SERVICE:##; ")
            append("s#:$SONY_ACCESSIBILITY_SERVICE${dollar}##; ")
            append("s#^$SONY_ACCESSIBILITY_SERVICE${dollar}##; ")
            append("s#:$SONY_ACCESSIBILITY_SERVICE:#:#'); ")
            append("if [ -n \"${dollar}next\" ] && [ \"${dollar}next\" != null ]; then ")
            append("settings put secure enabled_accessibility_services \"${dollar}next\"; ")
            append("else settings delete secure enabled_accessibility_services; ")
            append("settings put secure accessibility_enabled 0; fi; ")
        }
    }

    private companion object {
        const val PREFERENCES = "redirect_state"
        const val KEY_CONFIGURED = "configured"
        const val ADB_HOST = "127.0.0.1"
        const val ADB_PORT = 5555
        const val SOCKET_CONNECT_TIMEOUT_MS = 4_000
        const val SOCKET_READ_TIMEOUT_MS = 60_000
        const val GO3_COMPONENT = "ee.local.go3tvplus.debug/ee.local.go3tvplus.MainActivity"
        const val HELPER_PACKAGE = "ee.local.go3tvplus.tclredirect"
        const val SONY_ACCESSIBILITY_SERVICE =
            "ee.local.go3tvplus.tclredirect/.SonyButtonAccessibilityService"
        const val REMOTE_SCRIPT = "/data/local/tmp/go3-button-redirect.sh"
        const val REMOTE_PID = "/data/local/tmp/go3-button-redirect.pid"
        const val REMOTE_LOG = "/data/local/tmp/go3-button-redirect.log"
        const val LEGACY_TCL_REMOTE_SCRIPT = "/data/local/tmp/go3-prime-button-redirect.sh"
        const val LEGACY_TCL_REMOTE_PID = "/data/local/tmp/go3-prime-button-redirect.pid"
    }
}
