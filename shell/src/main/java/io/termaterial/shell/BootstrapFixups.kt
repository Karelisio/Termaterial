package io.termaterial.shell

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Post-extraction fixups making an unmodified Termux bootstrap usable from this app's own data
 * directory instead of Termux's (`/data/data/com.termux`, hardcoded at build time by
 * termux-packages and unreachable from any other app).
 *
 * Idempotent, so the same pass runs on a fresh extraction and, once, as a migration of an
 * installation extracted by an older Termaterial version ([isApplied] / [VERSION]).
 *
 * 1. Absolute symlinks into `/data/data/com.termux` are retargeted. Without this, every key in
 *    `etc/apt/trusted.gpg.d/` dangles and apt cannot verify any repository signature.
 * 2. Text files referencing `/data/data/com.termux` are rewritten with [TermuxPathRewriter]:
 *    script shebangs (`pkg`, `apt-key`, `top`, `login`, ... all start with
 *    `#!/data/data/com.termux/files/usr/bin/sh` or similar), `etc/profile`, pkg-config files...
 *    ELF binaries are left alone - a longer path cannot be patched into them in place - their
 *    compiled-in paths are handled at session start instead (see [BootstrapShellSessionFactory]).
 *    dpkg's database (`var/lib/dpkg`, except maintainer scripts) is deliberately left untouched:
 *    it must keep matching the paths inside Termux `.deb` archives.
 * 3. The bootstrap "second stage" (running the bootstrap packages' `postinst` scripts, normally
 *    done by the Termux app right after extraction) is marked as done, so that the fallback
 *    `etc/profile.d` script does not try to run it at first login: those scripts only register
 *    `update-alternatives` entries, whose compiled-in admin directory is unreachable here, so
 *    running them would only print errors.
 */
object BootstrapFixups {

    /** Bump whenever [apply] gains a new fixup, so existing installations get it on next launch. */
    const val VERSION = 1

    private const val MARKER_FILE_NAME = ".TERMATERIAL_FIXUPS_VERSION"

    /** Files larger than this are never considered text worth rewriting. */
    private const val MAX_TEXT_FILE_SIZE = 4L * 1024 * 1024

    /** How much of a file is inspected to decide whether it is text (no NUL byte, not ELF). */
    private const val TEXT_SNIFF_SIZE = 8 * 1024

    private val DPKG_MAINTAINER_SCRIPT_EXTENSIONS = setOf("preinst", "postinst", "prerm", "postrm", "config")

    data class Result(val rewrittenFiles: Int, val retargetedSymlinks: Int)

    fun isApplied(prefixDir: File): Boolean =
        runCatching { File(prefixDir, MARKER_FILE_NAME).readText().trim().toInt() }.getOrNull()
            ?.let { it >= VERSION } ?: false

    /**
     * Applies every fixup to the bootstrap extracted at [prefixDir] (whose parent layout mirrors
     * Termux's: `<realDataDir>/files/usr`), then records [VERSION] in a marker file inside it.
     */
    fun apply(prefixDir: File, realDataDir: File): Result {
        val rewriter = TermuxPathRewriter(realDataDir.absolutePath)
        val prefix = prefixDir.toPath()
        val dpkgDatabase = prefix.resolve("var/lib/dpkg")
        val dpkgMaintainerScripts = dpkgDatabase.resolve("info")
        var rewrittenFiles = 0
        var retargetedSymlinks = 0

        Files.walkFileTree(prefix, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) {
                    if (retargetSymlink(file, rewriter)) retargetedSymlinks++
                } else if (attrs.isRegularFile) {
                    val inDpkgDatabase = file.startsWith(dpkgDatabase)
                    val isMaintainerScript = file.parent == dpkgMaintainerScripts &&
                        file.fileName.toString().substringAfterLast('.') in DPKG_MAINTAINER_SCRIPT_EXTENSIONS
                    if ((!inDpkgDatabase || isMaintainerScript) && rewriteTextFile(file, attrs, rewriter)) {
                        rewrittenFiles++
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })

        markSecondStageDone(prefixDir)
        File(prefixDir, MARKER_FILE_NAME).writeText(VERSION.toString())
        return Result(rewrittenFiles, retargetedSymlinks)
    }

    private fun retargetSymlink(link: Path, rewriter: TermuxPathRewriter): Boolean {
        val target = Files.readSymbolicLink(link).toString()
        if (!target.startsWith("/")) return false
        val newTarget = rewriter.rewrite(target)
        if (newTarget == target) return false
        Files.delete(link)
        Files.createSymbolicLink(link, Paths.get(newTarget))
        return true
    }

    private fun rewriteTextFile(file: Path, attrs: BasicFileAttributes, rewriter: TermuxPathRewriter): Boolean {
        if (attrs.size() > MAX_TEXT_FILE_SIZE || !looksLikeText(file)) return false
        val rewritten = rewriter.rewrite(Files.readAllBytes(file)) ?: return false

        // Written next to the original and atomically moved over it, with the same permissions,
        // so an interruption can never leave a truncated script behind.
        val temp = file.resolveSibling(".${file.fileName}.termaterial-tmp")
        try {
            Files.write(temp, rewritten)
            Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.deleteIfExists(temp)
            throw e
        }
        return true
    }

    private fun looksLikeText(file: Path): Boolean {
        val head = ByteArray(TEXT_SNIFF_SIZE)
        val read = Files.newInputStream(file).use { input ->
            var total = 0
            while (total < head.size) {
                val n = input.read(head, total, head.size - total)
                if (n < 0) break
                total += n
            }
            total
        }
        val isElf = read >= 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        return !isElf && (0 until read).none { head[it] == 0.toByte() }
    }

    /**
     * Creates the lock symlink termux-bootstrap-second-stage.sh leaves behind once it has run
     * (see its own documentation in the bootstrap), which the `etc/profile.d` fallback checks.
     */
    private fun markSecondStageDone(prefixDir: File) {
        val secondStageDir = File(prefixDir, "etc/termux/termux-bootstrap/second-stage")
        val script = File(secondStageDir, "termux-bootstrap-second-stage.sh")
        val lock = File(secondStageDir, "termux-bootstrap-second-stage.sh.lock").toPath()
        if (script.isFile && !Files.exists(lock, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(lock, Paths.get(script.name))
        }
    }
}
