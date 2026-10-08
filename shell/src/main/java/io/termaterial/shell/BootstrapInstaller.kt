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
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Reported state of a [BootstrapInstaller.install] run, for driving a first-launch progress UI. */
sealed interface BootstrapProgress {
    data object CheckingExistingInstallation : BootstrapProgress
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : BootstrapProgress
    data class Extracting(val entriesDone: Int) : BootstrapProgress
    /** Rewriting the bootstrap's hardcoded Termux paths, see [BootstrapFixups]. */
    data object ApplyingFixups : BootstrapProgress
    data object Installed : BootstrapProgress
    data class Failed(val message: String, val cause: Throwable? = null) : BootstrapProgress
}

/**
 * Downloads and extracts an official Termux bootstrap archive (from termux-packages GitHub
 * releases) into this app's private storage, independently of [com.termux.terminal.TerminalSession]
 * so that reinstalling/updating the bootstrap later does not touch session/UI code.
 *
 * Kept separate from [BootstrapShellSessionFactory]: this class only ever produces a directory tree
 * under [TermaterialPaths.realPrefixDir]; it has no opinion on how that tree is later executed.
 */
class BootstrapInstaller(private val context: Context) {

    /**
     * True if a bootstrap was completely extracted - whichever [BOOTSTRAP_RELEASE_TAG] it came
     * from. A newer tag in a later app version must not trigger a reinstall on its own: that would
     * silently wipe every package the user installed since. Deliberately does not verify every
     * file, to keep app startup fast.
     */
    fun isInstalled(): Boolean =
        TermaterialPaths.installedVersionMarker(context).isFile &&
            TermaterialPaths.realBashBinary(context).isFile

    /**
     * Downloads and extracts the bootstrap if [isInstalled] is false (or [forceReinstall] is
     * true), reporting progress via the returned [Flow]; otherwise only applies [BootstrapFixups]
     * an older Termaterial version did not apply yet. Safe to collect from a Compose UI: all work
     * runs on [Dispatchers.IO].
     *
     * Everything is prepared in a staging directory first: an existing installation is only
     * replaced once the new one is complete, so a failed download or extraction never destroys it.
     */
    fun install(forceReinstall: Boolean = false): Flow<BootstrapProgress> = flow {
        emit(BootstrapProgress.CheckingExistingInstallation)

        val prefixDir = TermaterialPaths.realPrefixDir(context)
        val realDataDir = TermaterialPaths.realDataDir(context)

        if (!forceReinstall && isInstalled()) {
            if (!BootstrapFixups.isApplied(prefixDir)) {
                emit(BootstrapProgress.ApplyingFixups)
                BootstrapFixups.apply(prefixDir, realDataDir)
            }
            emit(BootstrapProgress.Installed)
            return@flow
        }

        val stagingDir = TermaterialPaths.realPrefixStagingDir(context)
        deleteTree(stagingDir)
        if (!stagingDir.mkdirs()) {
            throw IOException("Could not create staging directory: $stagingDir")
        }
        TermaterialPaths.realHomeDir(context).mkdirs()

        val arch = BootstrapArch.forSupportedAbis(Build.SUPPORTED_ABIS)
        val zipFile = File(context.cacheDir, arch.assetFileName)
        try {
            val sha256 = HttpDownload.download(bootstrapDownloadUrl(arch), zipFile) { bytesRead, totalBytes ->
                emit(BootstrapProgress.Downloading(bytesRead, totalBytes))
            }
            val expectedSha256 = BOOTSTRAP_SHA256.getValue(arch)
            if (!sha256.equals(expectedSha256, ignoreCase = true)) {
                throw IOException("Checksum mismatch for ${arch.assetFileName}: expected $expectedSha256, got $sha256")
            }

            extractBootstrapZip(zipFile, stagingDir) { entriesDone ->
                emit(BootstrapProgress.Extracting(entriesDone))
            }
        } finally {
            zipFile.delete()
        }

        emit(BootstrapProgress.ApplyingFixups)
        BootstrapFixups.apply(stagingDir, realDataDir)
        // Written before the swap below, so that the final directory is only ever seen complete.
        File(stagingDir, TermaterialPaths.installedVersionMarker(context).name).writeText(BOOTSTRAP_RELEASE_TAG)

        deleteTree(prefixDir)
        if (!stagingDir.renameTo(prefixDir)) {
            throw IOException("Could not move staging directory into place: $stagingDir -> $prefixDir")
        }

        emit(BootstrapProgress.Installed)
    }
        .catch { e ->
            // Using the `catch` operator (rather than a try/catch around the emit calls above)
            // so we don't violate Flow's exception transparency contract.
            runCatching { deleteTree(TermaterialPaths.realPrefixStagingDir(context)) }
            emit(BootstrapProgress.Failed(e.message ?: e.javaClass.simpleName, e))
        }
        .flowOn(Dispatchers.IO)

    private fun bootstrapDownloadUrl(arch: BootstrapArch): String =
        "$BOOTSTRAP_RELEASES_BASE_URL/$BOOTSTRAP_RELEASE_TAG/${arch.assetFileName}"

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
                    // Not Reader.forEachLine(): it calls use{} internally and would close zipInput
                    // (via InputStreamReader -> BufferedReader delegation) as soon as this entry's
                    // data ends, breaking every zip entry read afterwards ("Stream closed").
                    val symlinksReader = BufferedReader(InputStreamReader(zipInput, Charsets.UTF_8))
                    var line: String?
                    while (symlinksReader.readLine().also { line = it } != null) {
                        val currentLine = line!!
                        val parts = currentLine.split(SYMLINK_SEPARATOR)
                        if (parts.size != 2) throw IOException("Malformed symlink line: $currentLine")
                        val (linkTarget, relativeLinkPath) = parts
                        val linkPath = resolveInside(stagingDir, relativeLinkPath)
                        linkPath.parentFile?.mkdirs()
                        symlinks += linkTarget to linkPath.absolutePath
                    }
                } else {
                    val targetFile = resolveInside(stagingDir, entry.name)
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
                if (entriesDone % PROGRESS_EVERY_ENTRIES == 0) onEntry(entriesDone)
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        }
        onEntry(entriesDone)

        if (symlinks.isEmpty()) {
            throw IOException("Bootstrap zip did not contain a SYMLINKS.txt entry")
        }
        for ((target, linkPath) in symlinks) {
            Os.symlink(target, linkPath)
        }
    }

    companion object {
        /**
         * termux-packages bootstrap release tag installed on a fresh install. Bumping it only
         * affects new installations (see [isInstalled]); [BOOTSTRAP_SHA256] must be updated with
         * it. Verified against `git ls-remote --tags https://github.com/termux/termux-packages.git`
         * at the time this was written.
         */
        const val BOOTSTRAP_RELEASE_TAG = "bootstrap-2026.09.13-r1+apt.android-7"

        /** SHA-256 of each [BOOTSTRAP_RELEASE_TAG] asset, as downloaded from the release page. */
        private val BOOTSTRAP_SHA256 = mapOf(
            BootstrapArch.ARM64_V8A to "dbf2805613ff2ace0b233c3b349e080bb0ff358f4ad64f4cca5966b27b93e7ee",
            BootstrapArch.ARMEABI_V7A to "ac65b4c4aa10322a9a54f8ec3e3122dec1371bef4834db4455503df0fc33f521",
            BootstrapArch.X86_64 to "e5b7ce18642c6769acf6cd05586170191fc98f1882ddbde6b5fb5f0b1dc253f0",
            BootstrapArch.X86 to "790481a60eb90dfcbaccc27113c11fa14d6820215bec7860b9a03e3417ec8277",
        )

        private const val BOOTSTRAP_RELEASES_BASE_URL =
            "https://github.com/termux/termux-packages/releases/download"

        private val EXECUTABLE_PATH_PREFIXES = listOf("bin/", "libexec", "lib/apt/apt-helper", "lib/apt/methods")
        private const val EXECUTABLE_MODE = 448 // 0700 in octal
        /** U+2190 LEFTWARDS ARROW, the separator termux-packages uses in SYMLINKS.txt entries. */
        private const val SYMLINK_SEPARATOR = "←"

        private const val PROGRESS_EVERY_ENTRIES = 64
        private const val EXTRACT_BUFFER_SIZE = 32 * 1024

        /**
         * [relativePath] resolved under [dir], refusing anything that would land outside of it
         * (`../` components or an absolute path in an archive entry name - "zip slip").
         */
        internal fun resolveInside(dir: File, relativePath: String): File {
            val resolved = File(dir, relativePath).canonicalFile
            val root = dir.canonicalFile
            if (resolved != root && !resolved.path.startsWith(root.path + File.separator)) {
                throw IOException("Archive entry escapes the extraction directory: $relativePath")
            }
            return File(dir, relativePath)
        }

        /**
         * Recursively deletes [dir] without ever following symlinks - unlike
         * [File.deleteRecursively], which would descend into a symlinked directory and delete
         * the files it points to (a prefix can contain symlinks to anywhere).
         */
        internal fun deleteTree(dir: File) {
            val root = dir.toPath()
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
