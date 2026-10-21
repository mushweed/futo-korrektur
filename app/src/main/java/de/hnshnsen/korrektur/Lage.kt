package de.hnshnsen.korrektur

import android.app.ActivityManager
import java.io.File

/**
 * Wo der Prozess gerade rechnen darf. Aus der Tastatur heraus brauchte ein
 * Satz 30 s statt 1,5 s; das hier zeigt, ob Android die App dann auf die
 * Sparkerne setzt (cpuset) und für wie wichtig es sie hält.
 */
object Lage {

    fun jetzt(): String = listOf(prozess(), threads(), wichtigkeit()).joinToString(" · ")

    /** Die cpuset-Gruppe und die erlaubten Kerne des Prozesses. */
    private fun prozess(): String {
        val gruppe = lies("/proc/self/cgroup").lines()
            .filter { "cpuset" in it || it.startsWith("0::") }
            .joinToString(" ") { it.substringAfterLast(':') }
        val kerne = feld(lies("/proc/self/status"), "Cpus_allowed_list")
        return "Gruppe $gruppe, Kerne $kerne"
    }

    /** Die erlaubten Kerne je Thread, zusammengefasst ("0-3 ×6, 4-7 ×1"). */
    private fun threads(): String {
        val masken = File("/proc/self/task").listFiles().orEmpty().mapNotNull { t ->
            feld(lies("${t.path}/status"), "Cpus_allowed_list").takeIf { it.isNotEmpty() }
        }
        return "Threads " + masken.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} ×${it.value}" }
    }

    private fun wichtigkeit(): String {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        val name = when (info.importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "Vordergrund"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "Vordergrunddienst"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "sichtbar"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "wahrnehmbar"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "Dienst"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "zwischengespeichert"
            else -> "${info.importance}"
        }
        return "Wichtigkeit $name"
    }

    private fun lies(pfad: String) = try {
        File(pfad).readText()
    } catch (_: Exception) {
        ""
    }

    private fun feld(text: String, name: String) =
        text.lines().firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim().orEmpty()
}
