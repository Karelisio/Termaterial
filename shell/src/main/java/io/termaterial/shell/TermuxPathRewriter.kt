package io.termaterial.shell

/**
 * Rewrites references to Termux's own app data directory ([TERMUX_DATA_DIR]), which
 * termux-packages hardcodes at build time into the bootstrap's scripts, config files and
 * absolute symlinks, so that they point at this app's real data directory instead.
 *
 * The whole data directory is remapped (not just `files/usr`) because the bootstrap's layout
 * under it is the same as this app's: `files/usr` (prefix), `files/home` and `cache` (apt's
 * download cache, referenced as such by `pkg`).
 *
 * Only whole path references are rewritten:
 * - the match must not be followed by a path character other than `/`, so
 *   `/data/data/com.termux.api/...` (another Termux app) is left alone;
 * - the match must not be preceded by `.`, so a relative `./data/data/com.termux/...` archive
 *   member path (as listed in dpkg's database) is left alone.
 *
 * Works on raw bytes: bootstrap text files are not guaranteed to be UTF-8, and rewriting them
 * must not change any other byte.
 */
class TermuxPathRewriter(realDataDir: String) {

    private val replacement: ByteArray = realDataDir.trimEnd('/').toByteArray(Charsets.UTF_8)

    /** Returns [input] with every reference rewritten, or null if it contains none. */
    fun rewrite(input: ByteArray): ByteArray? {
        var matchStart = indexOfReference(input, 0)
        if (matchStart < 0) return null

        val output = java.io.ByteArrayOutputStream(input.size + 64)
        var copiedUpTo = 0
        while (matchStart >= 0) {
            output.write(input, copiedUpTo, matchStart - copiedUpTo)
            output.write(replacement)
            copiedUpTo = matchStart + NEEDLE.size
            matchStart = indexOfReference(input, copiedUpTo)
        }
        output.write(input, copiedUpTo, input.size - copiedUpTo)
        return output.toByteArray()
    }

    fun rewrite(text: String): String =
        rewrite(text.toByteArray(Charsets.UTF_8))?.toString(Charsets.UTF_8) ?: text

    private fun indexOfReference(input: ByteArray, from: Int): Int {
        var start = from
        while (true) {
            val index = indexOf(input, NEEDLE, start)
            if (index < 0) return -1
            val before = if (index > 0) input[index - 1] else null
            val after = input.getOrNull(index + NEEDLE.size)
            val precededByDot = before == '.'.code.toByte()
            val followedByPathChar = after != null && isPathCharacter(after)
            if (!precededByDot && !followedByPathChar) return index
            start = index + 1
        }
    }

    companion object {
        /** Termux's own data directory, as hardcoded throughout termux-packages builds. */
        const val TERMUX_DATA_DIR = "/data/data/com.termux"

        private val NEEDLE = TERMUX_DATA_DIR.toByteArray(Charsets.UTF_8)

        private fun isPathCharacter(byte: Byte): Boolean {
            val c = byte.toInt().toChar()
            return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-'
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
            val last = haystack.size - needle.size
            var i = from
            outer@ while (i <= last) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) {
                        i++
                        continue@outer
                    }
                }
                return i
            }
            return -1
        }
    }
}
