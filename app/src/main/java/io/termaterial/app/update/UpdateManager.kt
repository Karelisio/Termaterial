package io.termaterial.app.update

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.core.content.pm.PackageInfoCompat
import io.termaterial.app.R
import io.termaterial.shell.HttpDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

/** Download of an update APK in progress; [totalBytes] is -1 when unknown. */
data class DownloadProgress(val bytesRead: Long, val totalBytes: Long) {
    val fraction: Float? get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

data class UpdateUiState(
    val checking: Boolean = false,
    /** True once a check succeeded; [offer] is then null if the app is up to date. */
    val checked: Boolean = false,
    val checkError: String? = null,
    val offer: UpdateOffer? = null,
    val dialogVisible: Boolean = false,
    val download: DownloadProgress? = null,
    /** The APK was handed to the system installer, which now waits for the user. */
    val installing: Boolean = false,
    /** Why the last download or installation failed, shown in the update dialog. */
    val updateError: String? = null,
)

/**
 * In-app updates from the GitHub releases the CI publishes for every build: checks for newer
 * releases, downloads the newest APK (with progress, then verified against the SHA-256 GitHub
 * lists for it and against this app's package name and version) and hands it to Android's
 * installer. Process-wide, so a download survives the Activity being recreated.
 */
class UpdateManager(
    private val context: Context,
    val currentVersionName: String,
    private val currentVersionCode: Int,
    private val repository: String,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updatesDir = File(context.cacheDir, "updates")
    private val userAgent = "Termaterial/$currentVersionName"

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    init {
        // Whatever is left there is an APK that was installed (or abandoned) since.
        updatesDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Startup check, at most every [AUTO_CHECK_INTERVAL_MS]; the dialog only opens by itself for a
     * version the user has not already dismissed ("Later").
     */
    fun checkIfDue() {
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) >= AUTO_CHECK_INTERVAL_MS) {
            check(userInitiated = false)
        }
    }

    fun check(userInitiated: Boolean) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        _state.update { it.copy(checking = true, checkError = null) }
        checkJob = scope.launch {
            try {
                val offer = withContext(Dispatchers.IO) { fetchOffer() }
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                val dismissed = prefs.getInt(KEY_DISMISSED_VERSION_CODE, 0)
                _state.update {
                    it.copy(
                        checking = false,
                        checked = true,
                        offer = offer,
                        dialogVisible = offer != null && (userInitiated || offer.latest.versionCode != dismissed),
                        updateError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, checkError = describe(e)) }
            }
        }
    }

    /** Opens the dialog again for an offer found earlier (e.g. from the settings). */
    fun showOffer() {
        _state.update { if (it.offer != null) it.copy(dialogVisible = true) else it }
    }

    /**
     * Closes the dialog. Unless the system installer is already waiting for the user, this is
     * "Later": the dialog will not open by itself again for this version.
     */
    fun dismiss() {
        downloadJob?.cancel()
        val current = _state.value
        if (!current.installing) {
            current.offer?.let { prefs.edit().putInt(KEY_DISMISSED_VERSION_CODE, it.latest.versionCode).apply() }
        }
        _state.update { it.copy(dialogVisible = false, download = null, updateError = null) }
    }

    fun startUpdate() {
        val release = _state.value.offer?.latest ?: return
        if (downloadJob?.isActive == true) return
        _state.update { it.copy(download = DownloadProgress(0, release.apkSize), installing = false, updateError = null) }
        downloadJob = scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { downloadAndVerify(release) }
                _state.update { it.copy(download = null, installing = true) }
                withContext(Dispatchers.IO) { ApkInstaller.commit(context, apk) }
            } catch (e: CancellationException) {
                _state.update { it.copy(download = null) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(download = null, installing = false, updateError = describe(e)) }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Result of a committed installation, from [UpdateInstallReceiver]. */
    fun onInstallFinished(status: Int, message: String?) {
        _state.update {
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> it.copy(installing = false, dialogVisible = false)
                // Cancelled from the system's confirmation: back to the offer, nothing to report.
                PackageInstaller.STATUS_FAILURE_ABORTED -> it.copy(installing = false)
                else -> it.copy(
                    installing = false,
                    updateError = context.getString(R.string.update_error_install, message ?: status.toString()),
                )
            }
        }
    }

    private fun fetchOffer(): UpdateOffer? {
        val json = HttpDownload.getText(
            "https://api.github.com/repos/$repository/releases?per_page=$RELEASES_PER_PAGE",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "User-Agent" to userAgent,
            ),
        )
        return ReleaseParser.offerFor(ReleaseParser.parseReleases(json), currentVersionCode)
    }

    private suspend fun downloadAndVerify(release: AppRelease): File {
        updatesDir.mkdirs()
        updatesDir.listFiles()?.forEach { it.delete() }
        val apk = File(updatesDir, "termaterial-${release.versionName}.apk")
        var reported = 0L
        val sha256 = HttpDownload.download(release.apkUrl, apk, mapOf("User-Agent" to userAgent)) { read, total ->
            if (read - reported >= PROGRESS_STEP_BYTES || read == total) {
                reported = read
                _state.update { it.copy(download = DownloadProgress(read, if (total > 0) total else release.apkSize)) }
            }
        }
        try {
            if (release.apkSha256 != null && !sha256.equals(release.apkSha256, ignoreCase = true)) {
                throw IOException(context.getString(R.string.update_error_checksum))
            }
            val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)
                ?: throw IOException(context.getString(R.string.update_error_not_apk))
            if (info.packageName != context.packageName || PackageInfoCompat.getLongVersionCode(info) <= currentVersionCode) {
                throw IOException(context.getString(R.string.update_error_not_apk))
            }
        } catch (e: IOException) {
            apk.delete()
            throw e
        }
        return apk
    }

    private fun describe(e: Exception): String = when {
        e is HttpDownload.HttpStatusException && (e.code == 403 || e.code == 429) ->
            context.getString(R.string.update_error_rate_limited)
        e is UnknownHostException -> context.getString(R.string.update_error_offline)
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val PREFS_NAME = "termaterial_updates"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_DISMISSED_VERSION_CODE = "dismissed_version_code"
        const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val RELEASES_PER_PAGE = 30
        const val PROGRESS_STEP_BYTES = 64L * 1024
    }
}
