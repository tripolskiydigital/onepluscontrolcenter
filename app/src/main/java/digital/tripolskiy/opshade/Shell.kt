package digital.tripolskiy.opshade

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Runs shell commands as the adb shell user through Shizuku, so tiles that Android
 * no longer lets apps flip (Wi-Fi, Bluetooth, mobile data, airplane, dark mode) still work.
 * Without Shizuku every caller falls back to opening the matching settings panel.
 */
object Shell {
    private const val TAG = "OpShell"
    const val PERMISSION_REQUEST = 4201

    fun available(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun installedAndRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(PERMISSION_REQUEST)
        } catch (_: Throwable) {
        }
    }

    /** Runs [cmd] and returns its standard output, or null without Shizuku or on failure. */
    suspend fun output(cmd: String): String? = withContext(Dispatchers.IO) {
        if (!available()) return@withContext null
        try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() == 0) out else null
        } catch (t: Throwable) {
            Log.w(TAG, "'$cmd' failed", t)
            null
        }
    }

    /** Returns true when the command ran with exit code 0. */
    suspend fun run(cmd: String): Boolean = withContext(Dispatchers.IO) {
        if (!available()) return@withContext false
        try {
            // newProcess is private in API 13 but still the simplest way to run a one-off command.
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val code = p.waitFor()
            if (code != 0) Log.w(TAG, "'$cmd' exited $code: " + p.errorStream.bufferedReader().readText())
            code == 0
        } catch (t: Throwable) {
            Log.w(TAG, "'$cmd' failed", t)
            false
        }
    }
}
