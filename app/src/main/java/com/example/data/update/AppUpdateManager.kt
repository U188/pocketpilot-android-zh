package com.example.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.local.AppPreferencesRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class GitHubReleaseInfo(
    val tagName: String,
    val displayVersion: String,
    val buildNumber: Int,
    val releaseName: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkAssetName: String?,
    val apkDownloadUrl: String?,
)

sealed interface ApkDownloadState {
    data object Idle : ApkDownloadState

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : ApkDownloadState {
        val progress: Float?
            get() = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }

        val percent: Int?
            get() = progress?.let { (it * 100f).toInt().coerceIn(0, 100) }
    }

    data class Downloaded(
        val apkFile: File,
        val sizeBytes: Long,
    ) : ApkDownloadState

    data class Failed(val message: String) : ApkDownloadState

    data object Cancelled : ApkDownloadState
}

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val availableRelease: GitHubReleaseInfo? = null,
    val showUpdateDialog: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    val isUpToDate: Boolean = false,
    val downloadState: ApkDownloadState = ApkDownloadState.Idle,
)

class AppUpdateManager(
    context: Context,
    private val preferencesRepository: AppPreferencesRepository,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()

    @Volatile
    private var downloadJob: Job? = null

    @Volatile
    private var activeDownloadCall: Call? = null

    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    suspend fun checkForUpdates(force: Boolean) {
        if (!checkMutex.tryLock()) return
        try {
            val nowMs = clockMs()
            val currentSettings = preferencesRepository.settings.first()
            if (!force && !shouldAutoCheck(
                    autoCheckEnabled = currentSettings.autoCheckUpdates,
                    lastCheckedMs = currentSettings.lastUpdateCheckEpochMs,
                    nowMs = nowMs,
                )
            ) {
                return
            }

            _state.update {
                it.copy(
                    isChecking = true,
                    statusMessage = if (force) null else it.statusMessage,
                    isError = if (force) false else it.isError,
                    isUpToDate = if (force) false else it.isUpToDate,
                )
            }

            val result = fetchLatestRelease()
            runCatching { preferencesRepository.setLastUpdateCheckEpochMs(clockMs()) }

            result.onSuccess { release ->
                if (isUpdateAvailable(release, currentVersionCode)) {
                    _state.update { prev ->
                        val sameRelease = prev.availableRelease?.buildNumber == release.buildNumber
                        prev.copy(
                            isChecking = false,
                            availableRelease = release,
                            showUpdateDialog = true,
                            statusMessage = "有可用版本：${release.displayVersion}（构建 ${release.buildNumber}）",
                            isError = false,
                            isUpToDate = false,
                            downloadState = if (sameRelease) prev.downloadState else ApkDownloadState.Idle,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isChecking = false,
                            availableRelease = null,
                            showUpdateDialog = false,
                            statusMessage = if (force) {
                                "当前已是最新版本（$currentVersionName，构建 $currentVersionCode）"
                            } else {
                                it.statusMessage
                            },
                            isError = false,
                            isUpToDate = true,
                            downloadState = ApkDownloadState.Idle,
                        )
                    }
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val friendly = mapThrowableToMessage(err)
                _state.update {
                    it.copy(
                        isChecking = false,
                        statusMessage = if (force) friendly else it.statusMessage,
                        isError = if (force) true else it.isError,
                        isUpToDate = false,
                    )
                }
            }
        } finally {
            checkMutex.unlock()
        }
    }

    fun startDownload(release: GitHubReleaseInfo) {
        val downloadUrl = release.apkDownloadUrl?.takeIf { it.isNotBlank() }
        if (downloadUrl == null) {
            _state.update {
                it.copy(
                    downloadState = ApkDownloadState.Failed(
                        "版本 ${release.tagName} 中未找到 APK 文件（$TARGET_APK_NAME）。",
                    ),
                )
            }
            return
        }

        // Cancel any previous download before starting a fresh one
        activeDownloadCall?.cancel()
        downloadJob?.cancel()

        downloadJob = scope.launch {
            val updatesDir = File(appContext.filesDir, UPDATES_DIR_NAME).apply { mkdirs() }
            val finalFile = File(updatesDir, "pocketpilot-${release.buildNumber}.apk")
            val partFile = File(updatesDir, "pocketpilot-${release.buildNumber}.apk.part")

            try {
                // Clean stale APKs / partial files in internal updates directory
                updatesDir.listFiles()?.forEach { file ->
                    if (file.name != finalFile.name) {
                        runCatching { file.delete() }
                    }
                }

                // Reuse already downloaded valid APK if present
                if (finalFile.exists() && finalFile.length() > 0L && isValidApkMagic(finalFile)) {
                    _state.update {
                        it.copy(downloadState = ApkDownloadState.Downloaded(finalFile, finalFile.length()))
                    }
                    return@launch
                } else if (finalFile.exists()) {
                    runCatching { finalFile.delete() }
                }

                _state.update {
                    it.copy(downloadState = ApkDownloadState.Downloading(0L, -1L))
                }

                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "PocketPilot-Android/$currentVersionName")
                    .get()
                    .build()

                val call = downloadClient.newCall(request)
                activeDownloadCall = call

                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw GitHubUpdateException(
                            mapHttpErrorToMessage(
                                httpCode = response.code,
                                rateLimitRemaining = response.header("X-RateLimit-Remaining"),
                            ),
                        )
                    }

                    val body = response.body
                        ?: throw GitHubUpdateException("下载 APK 时服务器返回空响应")
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L
                    var lastEmitMs = 0L

                    _state.update {
                        it.copy(downloadState = ApkDownloadState.Downloading(0L, totalBytes))
                    }

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read

                                val now = System.currentTimeMillis()
                                if (now - lastEmitMs >= 80L) {
                                    lastEmitMs = now
                                    _state.update {
                                        it.copy(
                                            downloadState = ApkDownloadState.Downloading(
                                                downloadedBytes = downloadedBytes,
                                                totalBytes = totalBytes,
                                            ),
                                        )
                                    }
                                }
                            }
                            output.flush()
                        }
                    }

                    ensureActive()

                    if (downloadedBytes <= 0L || !isValidApkMagic(partFile)) {
                        runCatching { partFile.delete() }
                        throw GitHubUpdateException("下载的文件已损坏或不是有效的 APK")
                    }

                    if (finalFile.exists()) {
                        runCatching { finalFile.delete() }
                    }
                    if (!partFile.renameTo(finalFile)) {
                        partFile.copyTo(finalFile, overwrite = true)
                        runCatching { partFile.delete() }
                    }

                    _state.update {
                        it.copy(
                            downloadState = ApkDownloadState.Downloaded(
                                apkFile = finalFile,
                                sizeBytes = finalFile.length(),
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                runCatching { partFile.delete() }
                _state.update { it.copy(downloadState = ApkDownloadState.Cancelled) }
            } catch (err: Throwable) {
                runCatching { partFile.delete() }
                val isCallCancelled = activeDownloadCall?.isCanceled() == true
                if (isCallCancelled) {
                    _state.update { it.copy(downloadState = ApkDownloadState.Cancelled) }
                } else {
                    _state.update {
                        it.copy(
                            downloadState = ApkDownloadState.Failed(
                                mapThrowableToMessage(err),
                            ),
                        )
                    }
                }
            } finally {
                activeDownloadCall = null
            }
        }
    }

    fun cancelDownload() {
        activeDownloadCall?.cancel()
        downloadJob?.cancel()
        downloadJob = null
        activeDownloadCall = null
        _state.update { current ->
            if (current.downloadState is ApkDownloadState.Downloading) {
                current.copy(downloadState = ApkDownloadState.Cancelled)
            } else {
                current
            }
        }
    }

    fun dismissUpdateDialog() {
        _state.update { it.copy(showUpdateDialog = false) }
    }

    fun openUpdateDialog() {
        _state.update { current ->
            if (current.availableRelease != null) {
                current.copy(showUpdateDialog = true)
            } else {
                current
            }
        }
    }

    private suspend fun fetchLatestRelease(): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PocketPilot-Android/$currentVersionName")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val remaining = response.header("X-RateLimit-Remaining")
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw GitHubUpdateException(
                        mapHttpErrorToMessage(
                            httpCode = code,
                            rateLimitRemaining = remaining,
                            responseBody = body,
                        ),
                    )
                }

                parseLatestReleaseJson(body)
            }
        }
    }

    class GitHubUpdateException(message: String) : Exception(message)

    companion object {
        const val REPO_OWNER = "U188"
        const val REPO_NAME = "pocketpilot-android-zh"
        const val TARGET_APK_NAME = "app-debug.apk"
        const val UPDATES_DIR_NAME = "updates"
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        const val RELEASES_PAGE_URL =
            "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"
        const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

        private val INTEGER_REGEX = Regex("""\d+""")

        fun canRequestPackageInstalls(context: Context): Boolean {
            return runCatching {
                context.packageManager.canRequestPackageInstalls()
            }.getOrDefault(false)
        }

        fun buildUnknownSourcesSettingsIntent(context: Context): Intent {
            return Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        }

        fun buildInstallApkIntent(context: Context, apkFile: File): Intent {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        fun isValidApkMagic(file: File): Boolean {
            if (!file.exists() || file.length() < 4L) return false
            return runCatching {
                val header = ByteArray(4)
                file.inputStream().use { input ->
                    val read = input.read(header)
                    read == 4 &&
                        header[0] == 0x50.toByte() && // 'P'
                        header[1] == 0x4B.toByte() && // 'K'
                        header[2] == 0x03.toByte() &&
                        header[3] == 0x04.toByte()
                }
            }.getOrDefault(false)
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 0L) return "0 字节"
            val kb = 1024.0
            val mb = kb * 1024.0
            return when {
                bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
                bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
                else -> "$bytes 字节"
            }
        }

        fun shouldAutoCheck(
            autoCheckEnabled: Boolean,
            lastCheckedMs: Long,
            nowMs: Long,
        ): Boolean {
            if (!autoCheckEnabled) return false
            if (lastCheckedMs <= 0L) return true
            return (nowMs - lastCheckedMs) >= AUTO_CHECK_INTERVAL_MS
        }

        fun parseBuildNumberFromTag(tagName: String): Int? {
            val trimmed = tagName.trim()
            if (trimmed.isEmpty()) return null
            return INTEGER_REGEX.findAll(trimmed).lastOrNull()?.value?.toIntOrNull()
        }

        fun formatDisplayVersion(tagName: String, buildNumber: Int): String {
            val cleaned = tagName.trim().removePrefix("v").removePrefix("V")
            return cleaned.ifBlank { "1.0.$buildNumber" }
        }

        fun isUpdateAvailable(release: GitHubReleaseInfo, currentVersionCode: Int): Boolean {
            return release.buildNumber > currentVersionCode
        }

        fun parseLatestReleaseJson(rawJson: String): GitHubReleaseInfo {
            val root = try {
                JSONObject(rawJson)
            } catch (e: Exception) {
                throw GitHubUpdateException("GitHub 返回了无效响应（不是 JSON）")
            }

            val tagName = root.optString("tag_name", "").trim()
            val buildNumber = parseBuildNumberFromTag(tagName)
                ?: throw GitHubUpdateException(
                    if (tagName.isBlank()) {
                        "GitHub Release 未设置版本标签"
                    } else {
                        "无法从标签“$tagName”解析构建编号"
                    },
                )

            val displayVersion = formatDisplayVersion(tagName, buildNumber)
            val releaseName = root.optString("name", "").trim().ifBlank { "v$displayVersion" }
            val releaseNotes = root.optString("body", "").trim()
            val htmlUrl = root.optString("html_url", "").trim().ifBlank { RELEASES_PAGE_URL }

            var exactApkName: String? = null
            var exactApkUrl: String? = null
            var fallbackApkName: String? = null
            var fallbackApkUrl: String? = null

            val assets = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "").trim()
                    val downloadUrl = asset.optString("browser_download_url", "").trim()
                    if (name.isEmpty() || downloadUrl.isEmpty()) continue
                    if (name.equals(TARGET_APK_NAME, ignoreCase = true)) {
                        exactApkName = name
                        exactApkUrl = downloadUrl
                        break
                    }
                    if (fallbackApkUrl == null && name.endsWith(".apk", ignoreCase = true)) {
                        fallbackApkName = name
                        fallbackApkUrl = downloadUrl
                    }
                }
            }

            return GitHubReleaseInfo(
                tagName = tagName,
                displayVersion = displayVersion,
                buildNumber = buildNumber,
                releaseName = releaseName,
                releaseNotes = releaseNotes,
                htmlUrl = htmlUrl,
                apkAssetName = exactApkName ?: fallbackApkName,
                apkDownloadUrl = exactApkUrl ?: fallbackApkUrl,
            )
        }

        fun mapHttpErrorToMessage(
            httpCode: Int,
            rateLimitRemaining: String? = null,
            responseBody: String = "",
        ): String {
            return when {
                httpCode == 404 ->
                    "汉化仓库尚未发布 Release（HTTP 404）"
                httpCode == 429 || (httpCode == 403 && (rateLimitRemaining == "0" || responseBody.contains("rate limit", ignoreCase = true))) ->
                    "已达到 GitHub API 请求限制（未认证时每小时最多 60 次），请稍后重试。"
                httpCode == 403 ->
                    "GitHub Releases 暂时拒绝访问（HTTP 403），请稍后重试。"
                httpCode in 500..599 ->
                    "GitHub 服务器暂时不可用（HTTP $httpCode），请稍后重试。"
                else ->
                    "检查更新失败（GitHub HTTP $httpCode）"
            }
        }

        fun mapThrowableToMessage(err: Throwable): String {
            return when (err) {
                is GitHubUpdateException -> err.message ?: "检查更新时出错"
                is UnknownHostException -> "网络未连接或无法访问 GitHub"
                is SocketTimeoutException -> "等待 GitHub 响应超时"
                is SSLException -> "与 GitHub 建立安全连接（TLS）失败"
                is IOException -> "检查更新时发生网络错误：${err.localizedMessage ?: "无网络连接"}"
                else -> "检查更新失败：${err.localizedMessage ?: err::class.simpleName}"
            }
        }
    }
}
