package io.termaterial.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BootstrapShellSessionFactoryTest {

    private val realPrefixDir = File("/data/user/0/io.termaterial.app/files/usr")
    private val realHomeDir = File("/data/user/0/io.termaterial.app/files/home")

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
        val conf = BootstrapShellSessionFactory.buildAptConfigOverride(realPrefixDir)
        assertTrue(conf.contains("Dir \"${realPrefixDir.absolutePath}/\";"))
        assertTrue(conf.contains("Dir::State::status \"var/lib/dpkg/status\";"))
        assertTrue(conf.contains("Dir::Bin::methods \"${realPrefixDir.absolutePath}/lib/apt/methods\";"))
        assertTrue(conf.contains("Dir::Bin::dpkg \"${realPrefixDir.absolutePath}/bin/dpkg\";"))
    }
}
