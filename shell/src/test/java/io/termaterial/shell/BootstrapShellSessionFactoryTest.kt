package io.termaterial.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootstrapShellSessionFactoryTest {

    private val realPrefixDir = File("/data/user/0/io.termaterial.app/files/usr")
    private val realHomeDir = File("/data/user/0/io.termaterial.app/files/home")
    private val aptCacheDir = File("/data/user/0/io.termaterial.app/cache/apt")

    private fun envMap(env: List<String>): Map<String, String> =
        env.associate {
            val (key, value) = it.split("=", limit = 2)
            key to value
        }

    @Test
    fun `HOME PREFIX and PATH point at the real on-disk directories`() {
        val env = envMap(BootstrapShellSessionFactory.buildShellEnvironment(realPrefixDir, realHomeDir))
        assertEquals(realHomeDir.absolutePath, env["HOME"])
        assertEquals(realPrefixDir.absolutePath, env["PREFIX"])
        assertEquals("${realPrefixDir.absolutePath}/bin", env["PATH"])
    }

    @Test
    fun `LD_LIBRARY_PATH replaces the bootstrap binaries' hardcoded RUNPATH`() {
        val env = envMap(BootstrapShellSessionFactory.buildShellEnvironment(realPrefixDir, realHomeDir))
        assertEquals("${realPrefixDir.absolutePath}/lib", env["LD_LIBRARY_PATH"])
    }

    @Test
    fun `extra environment entries override defaults`() {
        val env = envMap(
            BootstrapShellSessionFactory.buildShellEnvironment(
                realPrefixDir,
                realHomeDir,
                extra = mapOf("TERM" to "screen-256color"),
            )
        )
        assertEquals("screen-256color", env["TERM"])
    }

    @Test
    fun `APT_CONFIG and DPKG_ADMINDIR point at the apt override and the real dpkg admindir`() {
        val env = envMap(BootstrapShellSessionFactory.buildShellEnvironment(realPrefixDir, realHomeDir))
        assertEquals(
            BootstrapShellSessionFactory.aptConfigFile(realPrefixDir).absolutePath,
            env["APT_CONFIG"],
        )
        assertEquals("${realPrefixDir.absolutePath}/var/lib/dpkg", env["DPKG_ADMINDIR"])
    }

    @Test
    fun `apt config override file sits next to, not inside, the real prefix`() {
        assertEquals(
            File(realPrefixDir.parentFile, "termaterial-apt.conf"),
            BootstrapShellSessionFactory.aptConfigFile(realPrefixDir),
        )
    }

    @Test
    fun `apt config override redirects Dir and every Dir-prefixed key apt would otherwise resolve wrong`() {
        val conf = BootstrapShellSessionFactory.buildAptConfigOverride(realPrefixDir, aptCacheDir)
        val prefix = realPrefixDir.absolutePath
        assertTrue(conf.contains("Dir \"$prefix/\";"))
        assertTrue(conf.contains("Dir::State::status \"var/lib/dpkg/status\";"))
        assertTrue(conf.contains("Dir::Bin::methods \"$prefix/lib/apt/methods\";"))
        assertTrue(conf.contains("Dir::Bin::dpkg \"$prefix/bin/dpkg\";"))
        assertTrue(conf.contains("Acquire::https::CaInfo \"$prefix/etc/tls/cert.pem\";"))
    }

    @Test
    fun `apt config override covers signature checks, decompressors, dpkg's PATH and the cache`() {
        val conf = BootstrapShellSessionFactory.buildAptConfigOverride(realPrefixDir, aptCacheDir)
        val prefix = realPrefixDir.absolutePath
        assertTrue(conf.contains("Dir::Bin::apt-key \"$prefix/bin/apt-key\";"))
        assertTrue(conf.contains("Dir::Bin::xz \"$prefix/bin/xz\";"))
        assertTrue(conf.contains("DPkg::Path \"$prefix/bin\";"))
        assertTrue(conf.contains("Dir::Cache \"${aptCacheDir.absolutePath}/\";"))
        assertFalse(conf.lines().filterNot { it.startsWith("//") }.any { it.contains("com.termux") })
    }

    @Test
    fun `passes Android runtime variables through, and nothing else from the app's environment`() {
        val env = envMap(
            BootstrapShellSessionFactory.buildShellEnvironment(
                realPrefixDir,
                realHomeDir,
                systemEnv = mapOf(
                    "ANDROID_ROOT" to "/system",
                    "BOOTCLASSPATH" to "/apex/x.jar",
                    "CLASSPATH" to "/data/app/base.apk",
                    "PATH" to "/system/bin",
                ),
            )
        )
        assertEquals("/system", env["ANDROID_ROOT"])
        assertEquals("/apex/x.jar", env["BOOTCLASSPATH"])
        assertFalse(env.containsKey("CLASSPATH"))
        assertEquals("${realPrefixDir.absolutePath}/bin", env["PATH"])
    }

    @Test
    fun `SHELL and the CA bundle variables point into the real prefix`() {
        val env = envMap(BootstrapShellSessionFactory.buildShellEnvironment(realPrefixDir, realHomeDir))
        assertEquals("${realPrefixDir.absolutePath}/bin/bash", env["SHELL"])
        assertEquals("${realPrefixDir.absolutePath}/etc/tls/cert.pem", env["SSL_CERT_FILE"])
        assertEquals("${realPrefixDir.absolutePath}/etc/tls/cert.pem", env["CURL_CA_BUNDLE"])
    }

    @Test
    fun `bash rc file replays Termux's login sequence from the real prefix`() {
        val rc = BootstrapShellSessionFactory.BASH_RC
        assertTrue(rc.contains(". \"\$PREFIX/etc/profile\""))
        assertTrue(rc.contains(". \"\$HOME/.bashrc\""))
        assertTrue(rc.contains(". \"\$HOME/.bash_profile\""))
        assertEquals(
            File(realPrefixDir.parentFile, "termaterial-bashrc"),
            BootstrapShellSessionFactory.bashRcFile(realPrefixDir),
        )
    }

    @Test
    fun `runtime directories include apt's partial download directories`() {
        val dirs = BootstrapShellSessionFactory.runtimeDirectories(realPrefixDir, aptCacheDir)
        assertTrue(File(aptCacheDir, "archives/partial") in dirs)
        assertTrue(File(realPrefixDir, "var/lib/apt/lists/partial") in dirs)
    }
}
