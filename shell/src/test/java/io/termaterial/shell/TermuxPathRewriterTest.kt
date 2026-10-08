package io.termaterial.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermuxPathRewriterTest {

    private val rewriter = TermuxPathRewriter("/data/user/0/io.termaterial.app")

    @Test
    fun `rewrites a script shebang`() {
        assertEquals(
            "#!/data/user/0/io.termaterial.app/files/usr/bin/sh\necho ok\n",
            rewriter.rewrite("#!/data/data/com.termux/files/usr/bin/sh\necho ok\n"),
        )
    }

    @Test
    fun `rewrites every reference, including the cache directory outside files`() {
        assertEquals(
            "a=/data/user/0/io.termaterial.app/files/usr b=\"/data/user/0/io.termaterial.app/cache/apt\"",
            rewriter.rewrite("a=/data/data/com.termux/files/usr b=\"/data/data/com.termux/cache/apt\""),
        )
    }

    @Test
    fun `rewrites references glued to compiler flags`() {
        assertEquals(
            "Libs: -L/data/user/0/io.termaterial.app/files/usr/lib",
            rewriter.rewrite("Libs: -L/data/data/com.termux/files/usr/lib"),
        )
    }

    @Test
    fun `rewrites the bare data directory when followed by a delimiter`() {
        assertEquals(
            "DIR=\"/data/user/0/io.termaterial.app\"",
            rewriter.rewrite("DIR=\"/data/data/com.termux\""),
        )
    }

    @Test
    fun `leaves other Termux apps' data directories alone`() {
        val text = "/data/data/com.termux.api/files /data/data/com.termux_x"
        assertEquals(text, rewriter.rewrite(text))
    }

    @Test
    fun `leaves relative archive member paths alone`() {
        val text = "./data/data/com.termux/files/usr/bin/bash"
        assertEquals(text, rewriter.rewrite(text))
    }

    @Test
    fun `returns null when there is nothing to rewrite`() {
        assertNull(rewriter.rewrite("#!/system/bin/sh\n".toByteArray()))
    }

    @Test
    fun `leaves non-UTF-8 bytes around a reference untouched`() {
        val input = byteArrayOf(0xE9.toByte(), ' '.code.toByte()) +
            "/data/data/com.termux/files".toByteArray() + byteArrayOf(0xFF.toByte())
        val expected = byteArrayOf(0xE9.toByte(), ' '.code.toByte()) +
            "/data/user/0/io.termaterial.app/files".toByteArray() + byteArrayOf(0xFF.toByte())
        assertArrayEquals(expected, rewriter.rewrite(input))
    }

    @Test
    fun `ignores a trailing slash on the real data directory`() {
        assertEquals("/x/files", TermuxPathRewriter("/x/").rewrite("/data/data/com.termux/files"))
    }
}
