package io.termaterial.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotModeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nativeLibDir = File("/data/app/io.termaterial.app/lib/arm64")
    private val binaries = ProotBinaries(
        proot = File(nativeLibDir, "libproot.so"),
        loader = File(nativeLibDir, "libproot-loader.so"),
        loader32 = File(nativeLibDir, "libproot-loader32.so"),
    )
    private val realDataDir = File("/data/user/0/io.termaterial.app")
    private val realPrefixDir = File(realDataDir, "files/usr")

    private fun envMap(env: List<String>): Map<String, String> =
        env.associate {
            val (key, value) = it.split("=", limit = 2)
            key to value
        }

    @Test
    fun `binds the app's data directory where Termux's is expected and runs Termux's login`() {
        val args = BootstrapShellSessionFactory.buildProotArguments(binaries, realDataDir)
        assertEquals(binaries.proot.absolutePath, args.first())
        val bind = args.indexOf("-b")
        assertEquals("/data/user/0/io.termaterial.app:/data/data/com.termux", args[bind + 1])
        val cwd = args.indexOf("-w")
        assertEquals("/data/data/com.termux/files/home", args[cwd + 1])
        assertTrue("--kill-on-exit" in args)
        assertTrue("--link2symlink" in args)
        assertEquals("/data/data/com.termux/files/usr/bin/login", args.last())
    }

    @Test
    fun `guest environment uses Termux's own paths and no direct-mode workaround`() {
        val env = envMap(BootstrapShellSessionFactory.buildProotEnvironment(binaries, realPrefixDir))
        assertEquals("/data/data/com.termux/files/home", env["HOME"])
        assertEquals("/data/data/com.termux/files/usr", env["PREFIX"])
        assertEquals("/data/data/com.termux/files/usr/bin", env["PATH"])
        assertEquals("/data/data/com.termux/files/usr/tmp", env["TMPDIR"])
        assertEquals("apt", env["TERMUX_APP_PACKAGE_MANAGER"])
        assertFalse(env.containsKey("LD_LIBRARY_PATH"))
        assertFalse(env.containsKey("APT_CONFIG"))
        assertFalse(env.containsKey("DPKG_ADMINDIR"))
    }

    @Test
    fun `tells proot where its loaders and temporary directory really are`() {
        val env = envMap(BootstrapShellSessionFactory.buildProotEnvironment(binaries, realPrefixDir))
        assertEquals(binaries.loader.absolutePath, env["PROOT_LOADER"])
        assertEquals(binaries.loader32!!.absolutePath, env["PROOT_LOADER_32"])
        assertEquals("/data/user/0/io.termaterial.app/files/usr/tmp", env["PROOT_TMP_DIR"])
    }

    @Test
    fun `omits the 32-bit loader when there is none and passes Android variables through`() {
        val env = envMap(
            BootstrapShellSessionFactory.buildProotEnvironment(
                binaries.copy(loader32 = null),
                realPrefixDir,
                systemEnv = mapOf("ANDROID_ROOT" to "/system", "CLASSPATH" to "/x.apk"),
            )
        )
        assertFalse(env.containsKey("PROOT_LOADER_32"))
        assertEquals("/system", env["ANDROID_ROOT"])
        assertFalse(env.containsKey("CLASSPATH"))
    }

    @Test
    fun `finds the binaries packaged in the native library directory, or nothing`() {
        val dir = tmp.newFolder("lib")
        assertNull(ProotBinaries.find(dir))

        File(dir, "libproot.so").writeText("")
        assertNull(ProotBinaries.find(dir))

        File(dir, "libproot-loader.so").writeText("")
        val found = ProotBinaries.find(dir)!!
        assertEquals(File(dir, "libproot.so"), found.proot)
        assertNull(found.loader32)

        File(dir, "libproot-loader32.so").writeText("")
        assertEquals(File(dir, "libproot-loader32.so"), ProotBinaries.find(dir)!!.loader32)
    }
}
