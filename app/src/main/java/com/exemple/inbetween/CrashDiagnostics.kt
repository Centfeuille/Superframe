package com.example.inbetween

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/** Small, local-only diagnostics store. It deliberately has no network or project-file access. */
object CrashDiagnostics {
    private const val MAX_LOG_BYTES = 256 * 1024
    private const val LOG_NAME = "inbetween-diagnostics.log"
    private const val START_NAME = "startup-in-progress.txt"
    @Volatile private var installed = false
    private val lock = Any()
    private lateinit var appContext: Context

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                    log("FATAL", "Uncaught exception on ${thread.name}: ${error.javaClass.name}: ${error.message}\n$trace")
                }
                if (previous != null) previous.uncaughtException(thread, error)
                else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
            installed = true
            log("INFO", "Crash diagnostics handler installed")
        }
    }

    fun beginStartup(): String? {
        val marker = File(appContext.filesDir, START_NAME)
        val previousFailure = runCatching { marker.takeIf { it.exists() }?.readText() }.getOrNull()
        if (!previousFailure.isNullOrBlank()) {
            log("WARN", "Previous launch did not reach the healthy-startup checkpoint: $previousFailure")
        }
        runCatching { marker.writeText("Startup began: ${now()}\n") }
        log("INFO", "Startup began; Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        return previousFailure
    }

    fun markHealthy() {
        runCatching { File(appContext.filesDir, START_NAME).delete() }
        log("INFO", "Startup reached the Compose UI checkpoint")
    }

    fun log(level: String, message: String) {
        runCatching {
            val file = File(appContext.filesDir, LOG_NAME)
            synchronized(lock) {
                file.parentFile?.mkdirs()
                file.appendText("${now()} [$level] $message\n")
                if (file.length() > MAX_LOG_BYTES) {
                    val old = file.readText()
                    file.writeText(old.takeLast(MAX_LOG_BYTES / 2))
                }
            }
        }
    }

    fun readLog(): String = runCatching {
        File(appContext.filesDir, LOG_NAME).takeIf { it.exists() }?.readText() ?: "No diagnostic events recorded yet."
    }.getOrElse { "Unable to read diagnostic log: ${it.message}" }

    fun makeReport(): ByteArray {
        val packageInfo = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0) }.getOrNull()
        val info = buildString {
            appendLine("Inbetween diagnostic report")
            appendLine("Generated: ${now()}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("App ID: ${appContext.packageName}")
            appendLine("App version: ${packageInfo?.versionName ?: "unknown"} (${packageInfo?.versionCode ?: 0})")
            appendLine("\nPrivacy: this report contains app diagnostic messages and device/app version details only. It does not include drawings or project files.")
        }
        return ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("device-and-app.txt")); zip.write(info.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("application-log.txt")); zip.write(readLog().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                // Performance profiler files (summary, events, frame / media / GL / system / per-thread statistics, test session). Only when it has data.
                if (Perf.hasData()) runCatching {
                    val versionName = packageInfo?.versionName ?: "unknown"
                    Perf.exportFiles("$versionName (${packageInfo?.versionCode ?: 0})", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", "${Build.MANUFACTURER} ${Build.MODEL}")
                        .forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
                }.onFailure { log("ERROR", "Performance report files failed: ${it.message}") }
                val marker = runCatching { File(appContext.filesDir, START_NAME).takeIf { it.exists() }?.readText() }.getOrNull()
                if (!marker.isNullOrBlank()) {
                    zip.putNextEntry(ZipEntry("startup-status.txt")); zip.write(marker.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}
