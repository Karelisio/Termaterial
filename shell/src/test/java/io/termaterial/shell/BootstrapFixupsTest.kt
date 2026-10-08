package io.termaterial.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

class BootstrapFixupsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dataDir by lazy { tmp.newFolder("io.termaterial.app") }
    private val prefix by lazy { File(dataDir, "files/usr").apply { mkdirs() } }

    private fun file(path: String, content: String, mode: String = "rw-------"): File =
        File(prefix, path).apply {
            parentFile!!.mkdirs()
            writeText(content)
            Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString(mode))
        }

    private fun symlink(path: String, target: String): File =
        File(prefix, path).apply {
            parentFile!!.mkdirs()
            Files.createSymbolicLink(toPath(), Paths.get(target))
        }

    private fun linkTarget(path: String): String = Files.readSymbolicLink(File(prefix, path).toPath()).toString()

    @Test
    fun `rewrites script shebangs and keeps their permissions`() {
        val pkg = file("bin/pkg", "#!/data/data/com.termux/files/usr/bin/bash\nsource /data/data/com.termux/files/usr/bin/x\n", "rwx------")

        val result = BootstrapFixups.apply(prefix, dataDir)

        assertEquals(
            "#!${dataDir.absolutePath}/files/usr/bin/bash\nsource ${dataDir.absolutePath}/files/usr/bin/x\n",
            pkg.readText(),
        )
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(pkg.toPath())))
        assertEquals(1, result.rewrittenFiles)
    }

    @Test
    fun `retargets absolute symlinks into the Termux data directory, not relative ones`() {
        symlink("etc/apt/trusted.gpg.d/grimler.gpg", "/data/data/com.termux/files/usr/share/termux-keyring/grimler.gpg")
        symlink("bin/ls", "coreutils")
        symlink("bin/sh", "/system/bin/sh")

        val result = BootstrapFixups.apply(prefix, dataDir)

        assertEquals("${dataDir.absolutePath}/files/usr/share/termux-keyring/grimler.gpg", linkTarget("etc/apt/trusted.gpg.d/grimler.gpg"))
        assertEquals("coreutils", linkTarget("bin/ls"))
        assertEquals("/system/bin/sh", linkTarget("bin/sh"))
        assertEquals(1, result.retargetedSymlinks)
    }

    @Test
    fun `leaves ELF and other binary files untouched`() {
        val elf = File(prefix, "bin/bash").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + "/data/data/com.termux/files/usr/lib".toByteArray())
        }
        val gz = File(prefix, "share/man/man1/x.1.gz").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0) + "/data/data/com.termux/files".toByteArray())
        }
        val elfBefore = elf.readBytes()
        val gzBefore = gz.readBytes()

        BootstrapFixups.apply(prefix, dataDir)

        assertTrue(elfBefore.contentEquals(elf.readBytes()))
        assertTrue(gzBefore.contentEquals(gz.readBytes()))
    }

    @Test
    fun `leaves the dpkg database alone but rewrites maintainer scripts`() {
        val list = file("var/lib/dpkg/info/nano.list", "/data/data/com.termux/files/usr/bin/nano\n")
        val status = file("var/lib/dpkg/status", "Conffiles:\n /data/data/com.termux/files/usr/etc/nanorc abc\n")
        val postinst = file("var/lib/dpkg/info/nano.postinst", "#!/data/data/com.termux/files/usr/bin/sh\n", "rwx------")

        BootstrapFixups.apply(prefix, dataDir)

        assertEquals("/data/data/com.termux/files/usr/bin/nano\n", list.readText())
        assertTrue(status.readText().contains(" /data/data/com.termux/files/usr/etc/nanorc"))
        assertEquals("#!${dataDir.absolutePath}/files/usr/bin/sh\n", postinst.readText())
    }

    @Test
    fun `marks the bootstrap second stage as done`() {
        file("etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh", "#!/bin/sh\n", "rwx------")

        BootstrapFixups.apply(prefix, dataDir)

        assertEquals(
            "termux-bootstrap-second-stage.sh",
            linkTarget("etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh.lock"),
        )
    }

    @Test
    fun `is idempotent and recorded in a version marker`() {
        file("bin/top", "#!/data/data/com.termux/files/usr/bin/sh\n")
        file("etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh", "#!/bin/sh\n")
        assertFalse(BootstrapFixups.isApplied(prefix))

        BootstrapFixups.apply(prefix, dataDir)
        assertTrue(BootstrapFixups.isApplied(prefix))

        val second = BootstrapFixups.apply(prefix, dataDir)
        assertEquals(BootstrapFixups.Result(rewrittenFiles = 0, retargetedSymlinks = 0), second)
    }
}
