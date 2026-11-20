package com.example.crash

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

data class CrashReport(
    val timestampMillis: Long,
    val threadName: String,
    val exceptionTitle: String,
    val stackTrace: String,
    val deviceSummary: String,
) {
    fun formatForClipboard(): String {
        val time = runCatching {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))
        }.getOrDefault(timestampMillis.toString())

        return buildString {
            appendLine("=== OpenCode Crash Report ===")
            appendLine("Time: $time")
            appendLine("Device: $deviceSummary")
            appendLine("Thread: $threadName")
            appendLine("Error: $exceptionTitle")
            appendLine()
            append(stackTrace)
        }
    }
}

object CrashReporter {
    private const val PREFS_NAME = "opencode_crash_reporter"
    private const val KEY_HAS_CRASH = "has_crash"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_THREAD = "thread"
    private const val KEY_TITLE = "title"
    private const val KEY_STACKTRACE = "stacktrace"
    private const val KEY_DEVICE = "device"
    private const val FALLBACK_FILE_NAME = "last_crash_report.txt"
    private const val MAX_STACKTRACE_CHARS = 32_000

    private val installed = AtomicBoolean(false)
    private val handlingCrash = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (handlingCrash.compareAndSet(false, true)) {
                runCatching {
                    saveCrash(appContext, thread.name, throwable)
                }
            }
            if (previousHandler != null && previousHandler !== Thread.getDefaultUncaughtExceptionHandler()) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun saveCrash(context: Context, threadName: String, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val title = buildString {
            append(throwable.javaClass.name)
            throwable.message?.takeIf { it.isNotBlank() }?.let { msg ->
                append(": ")
                append(msg.take(300))
            }
        }
        val rawStack = StringWriter().also { sw ->
            throwable.printStackTrace(PrintWriter(sw))
        }.toString().take(MAX_STACKTRACE_CHARS)

        val device = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}, app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        // Commit synchronously so the crash survives immediate process termination.
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_HAS_CRASH, true)
                .putLong(KEY_TIMESTAMP, now)
                .putString(KEY_THREAD, threadName)
                .putString(KEY_TITLE, title)
                .putString(KEY_STACKTRACE, rawStack)
                .putString(KEY_DEVICE, device)
                .commit()
        }

        // Redundant file backup in case SharedPreferences could not flush.
        runCatching {
            File(context.applicationContext.filesDir, FALLBACK_FILE_NAME).writeText(
                buildString {
                    appendLine(now)
                    appendLine(threadName)
                    appendLine(title)
                    appendLine(device)
                    append(rawStack)
                },
            )
        }
    }

    fun readPendingCrash(context: Context): CrashReport? {
        val appContext = context.applicationContext
        val fromPrefs = runCatching {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_HAS_CRASH, false)) return@runCatching null
            val stack = prefs.getString(KEY_STACKTRACE, null)?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            CrashReport(
                timestampMillis = prefs.getLong(KEY_TIMESTAMP, System.currentTimeMillis()),
                threadName = prefs.getString(KEY_THREAD, "unknown").orEmpty(),
                exceptionTitle = prefs.getString(KEY_TITLE, "Uncaught exception").orEmpty(),
                stackTrace = stack,
                deviceSummary = prefs.getString(KEY_DEVICE, "").orEmpty(),
            )
        }.getOrNull()

        if (fromPrefs != null) return fromPrefs

        return runCatching {
            val file = File(appContext.filesDir, FALLBACK_FILE_NAME)
            if (!file.exists()) return@runCatching null
            val lines = file.readLines()
            if (lines.size < 5) return@runCatching null
            CrashReport(
                timestampMillis = lines[0].toLongOrNull() ?: System.currentTimeMillis(),
                threadName = lines[1],
                exceptionTitle = lines[2],
                deviceSummary = lines[3],
                stackTrace = lines.drop(4).joinToString("\n"),
            )
        }.getOrNull()
    }

    fun clearPendingCrash(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        runCatching {
            File(appContext.filesDir, FALLBACK_FILE_NAME).delete()
        }
    }
}
