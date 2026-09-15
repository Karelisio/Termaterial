package io.termaterial.shell

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Reported state of a [BootstrapInstaller.install] run, for driving a first-launch progress UI. */
sealed interface BootstrapProgress {
    data object CheckingExistingInstallation : BootstrapProgress
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : BootstrapProgress
    data class Extracting(val entriesDone: Int) : BootstrapProgress
    data object Installed : BootstrapProgress
    data class Failed(val message: String, val cause: Throwable? = null) : BootstrapProgress
}

/**
 * Downloads and extracts an official Termux bootstrap archive (from termux-packages GitHub
 * releases) into this app's private storage, independently of [com.termux.terminal.TerminalSession]
 * so that reinstalling/updating the bootstrap later does not touch session/UI code.
 *
 * Kept separate from [ProotShellSessionFactory]: this class only ever produces a directory tree
 * under [TermaterialPaths.realPrefixDir]; it has no opinion on how that tree is later executed.
 */
class BootstrapInstaller(private val context: Context) {

    /**
     * True if a bootstrap matching [BOOTSTRAP_RELEASE_TAG] is already extracted and ready to use.
     * Deliberately does not verify every file - only that installation completed and was not
     * superseded by a different release tag - to keep app startup fast.
     */
    fun isInstalled(): Boolean {
        val marker = TermaterialPaths.installedVersionMarker(context)
        if (!marker.isFile) return false
        return runCatching { marker.readText().trim() }.getOrNull() == BOOTSTRAP_RELEASE_TAG
    }

    /**
     * Downloads and extracts the bootstrap if [isInstalled] is false (or [forceReinstall] is
     * true), reporting progress via the returned [Flow]. Safe to collect from a Compose UI: all
     * work runs on [Dispatchers.IO].
     */
    fun install(forceReinstall: Boolean = false): Flow<BootstrapProgress> = flow {
        emit(BootstrapProgress.CheckingExistingInstallation)

        if (!forceReinstall && isInstalled()) {
            emit(BootstrapProgress.Installed)
            return@flow
        }

        val stagingDir = TermaterialPaths.realPrefixStagingDir(context)
        val prefixDir = TermaterialPaths.realPrefixDir(context)
        val homeDir = TermaterialPaths.realHomeDir(context)

        stagingDir.deleteRecursively()
        prefixDir.deleteRecursively()
        if (!stagingDir.mkdirs()) {
            throw IOException("Could not create staging directory: $stagingDir")
        }
        homeDir.mkdirs()

        val arch = BootstrapArch.forSupportedAbis(Build.SUPPORTED_ABIS)
        val zipFile = File(context.cacheDir, arch.assetFileName)

        downloadWithProgress(bootstrapDownloadUrl(arch), zipFile) { bytesRead, totalBytes ->
            emit(BootstrapProgress.Downloading(bytesRead, totalBytes))
        }

        extractBootstrapZip(zipFile, stagingDir) { entriesDone ->
            emit(BootstrapProgress.Extracting(entriesDone))
        }

        zipFile.delete()

        if (!stagingDir.renameTo(prefixDir)) {
            throw IOException("Could not move staging directory into place: $stagingDir -> $prefixDir")
        }

        TermaterialPaths.installedVersionMarker(context).writeText(BOOTSTRAP_RELEASE_TAG)

        emit(BootstrapProgress.Installed)
    }
        .catch { e ->
            // Using the `catch` operator (rather than a try/catch around the emit calls above)
            // so we don't violate Flow's exception transparency contract.
            TermaterialPaths.realPrefixStagingDir(context).deleteRecursively()
            emit(BootstrapProgress.Failed(e.message ?: e.javaClass.simpleName, e))
        }
        .flowOn(Dispatchers.IO)

    private fun bootstrapDownloadUrl(arch: BootstrapArch): String =
        "$BOOTSTRAP_RELEASES_BASE_URL/$BOOTSTRAP_RELEASE_TAG/${arch.assetFileName}"

    private suspend fun downloadWithProgress(
        urlString: String,
        destFile: File,
        onProgress: suspend (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        var currentUrl = urlString
        var connection: HttpURLConnection? = null
        try {
            var redirects = 0
            while (true) {
                val url = URL(currentUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.connect()

                val code = conn.responseCode
                if (code in HTTP_REDIRECT_CODES) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect from $currentUrl had no Location header")
                    conn.disconnect()
                    redirects++
                    if (redirects > MAX_REDIRECTS) {
                        throw IOException("Too many redirects while downloading $urlString")
                    }
                    currentUrl = location
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    throw IOException("Unexpected HTTP $code while downloading $currentUrl")
                }

                connection = conn
                break
            }

            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var bytesRead = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        onProgress(bytesRead, totalBytes)
                    }
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extracts [zipFile] (an unmodified `bootstrap-<arch>.zip` from termux-packages) into
     * [stagingDir]. Mirrors termux-app's own extraction rules: a `SYMLINKS.txt` entry lists
     * symlinks as `target<LEFT ARROW>linkPath` (recreated after every real file is written), and
     * files under `bin/`, `libexec`, `lib/apt/apt-helper` or `lib/apt/methods` get the executable
     * bit set.
     */
    private suspend fun extractBootstrapZip(
        zipFile: File,
        stagingDir: File,
        onEntry: suspend (entriesDone: Int) -> Unit,
    ) {
        val buffer = ByteArray(EXTRACT_BUFFER_SIZE)
        val symlinks = mutableListOf<Pair<String, String>>()
        var entriesDone = 0

        ZipInputStream(zipFile.inputStream().buffered()).use { zipInput ->
            var entry: ZipEntry? = zipInput.nextEntry
            while (entry != null) {
                if (entry.name == "SYMLINKS.txt") {
                    BufferedReader(InputStreamReader(zipInput, Charsets.UTF_8)).forEachLine { line ->
                        val parts = line.split(SYMLINK_SEPARATOR)
                        if (parts.size != 2) throw IOException("Malformed symlink line: $line")
                        val (linkTarget, relativeLinkPath) = parts
                        val linkPath = File(stagingDir, relativeLinkPath)
                        linkPath.parentFile?.mkdirs()
                        symlinks += linkTarget to linkPath.absolutePath
                    }
                } else {
                    val targetFile = File(stagingDir, entry.name)
                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { out ->
                            var read: Int
                            while (zipInput.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                            }
                        }
                        if (EXECUTABLE_PATH_PREFIXES.any { entry!!.name.startsWith(it) }) {
                            Os.chmod(targetFile.absolutePath, EXECUTABLE_MODE)
                        }
                    }
                }
                entriesDone++
                onEntry(entriesDone)
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        }

        if (symlinks.isEmpty()) {
            throw IOException("Bootstrap zip did not contain a SYMLINKS.txt entry")
        }
        for ((target, linkPath) in symlinks) {
            Os.symlink(target, linkPath)
        }
    }

    companion object {
        /**
         * termux-packages bootstrap release tag to install. Bump this (and re-run the app's
         * reinstall flow) to pick up a newer bootstrap; verified against
         * `git ls-remote --tags https://github.com/termux/termux-packages.git` at the time this
         * was written.
         */
        const val BOOTSTRAP_RELEASE_TAG = "bootstrap-2026.09.13-r1+apt.android-7"

        private const val BOOTSTRAP_RELEASES_BASE_URL =
            "https://github.com/termux/termux-packages/releases/download"

        private val EXECUTABLE_PATH_PREFIXES = listOf("bin/", "libexec", "lib/apt/apt-helper", "lib/apt/methods")
        private const val EXECUTABLE_MODE = 448 // 0700 in octal
        /** U+2190 LEFTWARDS ARROW, the separator termux-packages uses in SYMLINKS.txt entries. */
        private const val SYMLINK_SEPARATOR = "←"

        private const val DOWNLOAD_BUFFER_SIZE = 32 * 1024
        private const val EXTRACT_BUFFER_SIZE = 32 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        private val HTTP_REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}
