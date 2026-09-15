package io.termaterial.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotShellSessionFactoryTest {

    private val realPrefixDir = File("/data/user/0/io.termaterial.app/files/usr")
    private val realHomeDir = File("/data/user/0/io.termaterial.app/files/home")

    @Test
    fun `argv0 is the real proot path so execvp runs the right binary`() {
        val argv = ProotShellSessionFactory.buildProotArgv(
            prootPath = "${realPrefixDir.absolutePath}/bin/proot",
            realPrefixDir = realPrefixDir,
            realHomeDir = realHomeDir,
            externalStorageDir = null,
            apexBindRequired = false,
        )
        assertEquals("${realPrefixDir.absolutePath}/bin/proot", argv[0])
    }

    @Test
    fun `binds the real prefix and home onto the virtual com-termux paths`() {
        val argv = ProotShellSessionFactory.buildProotArgv(
            prootPath = "proot",
            realPrefixDir = realPrefixDir,
            realHomeDir = realHomeDir,
            externalStorageDir = null,
            apexBindRequired = false,
        )
        assertTrue(argv.contains("${realPrefixDir.absolutePath}:${TermaterialPaths.VIRTUAL_PREFIX}"))
        assertTrue(argv.contains("${realHomeDir.absolutePath}:${TermaterialPaths.VIRTUAL_HOME}"))
        assertTrue(argv.contains("${TermaterialPaths.VIRTUAL_PREFIX}/bin/bash"))
        assertTrue(argv.contains("--login"))
    }

    @Test
    fun `only binds apex on API 29+`() {
        val withoutApex = ProotShellSessionFactory.buildProotArgv(
            "proot", realPrefixDir, realHomeDir, externalStorageDir = null, apexBindRequired = false,
        )
        val withApex = ProotShellSessionFactory.buildProotArgv(
            "proot", realPrefixDir, realHomeDir, externalStorageDir = null, apexBindRequired = true,
        )
        assertTrue(!withoutApex.contains("/apex"))
        assertTrue(withApex.contains("/apex"))
    }

    @Test
    fun `binds external storage to sdcard only when available`() {
        val sdcard = File("/storage/emulated/0")
        val argv = ProotShellSessionFactory.buildProotArgv(
            "proot", realPrefixDir, realHomeDir, externalStorageDir = sdcard, apexBindRequired = false,
        )
        assertTrue(argv.contains("${sdcard.absolutePath}:/sdcard"))
    }

    @Test
    fun `environment always points at the virtual prefix and home`() {
        val env = ProotShellSessionFactory.buildShellEnvironment().associate {
            val (key, value) = it.split("=", limit = 2)
            key to value
        }
        assertEquals(TermaterialPaths.VIRTUAL_HOME, env["HOME"])
        assertEquals(TermaterialPaths.VIRTUAL_PREFIX, env["PREFIX"])
        assertEquals("${TermaterialPaths.VIRTUAL_PREFIX}/bin", env["PATH"])
    }

    @Test
    fun `extra environment entries override defaults`() {
        val env = ProotShellSessionFactory.buildShellEnvironment(mapOf("TERM" to "screen-256color")).associate {
            val (key, value) = it.split("=", limit = 2)
            key to value
        }
        assertEquals("screen-256color", env["TERM"])
    }
}
