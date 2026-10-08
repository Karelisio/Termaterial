package io.termaterial.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class BootstrapInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `resolveInside accepts paths inside the extraction directory`() {
        val dir = tmp.newFolder("staging")
        assertEquals(File(dir, "bin/bash"), BootstrapInstaller.resolveInside(dir, "bin/bash"))
        assertEquals(File(dir, "./etc/../etc/profile"), BootstrapInstaller.resolveInside(dir, "./etc/../etc/profile"))
    }

    @Test
    fun `resolveInside refuses entries escaping the extraction directory`() {
        val dir = tmp.newFolder("staging")
        assertThrows(IOException::class.java) { BootstrapInstaller.resolveInside(dir, "../usr/bin/evil") }
        assertThrows(IOException::class.java) { BootstrapInstaller.resolveInside(dir, "bin/../../../evil") }
        assertThrows(IOException::class.java) { BootstrapInstaller.resolveInside(File(dir, "x"), "../x-sibling/evil") }
    }

    @Test
    fun `deleteTree deletes symlinks, never what they point to`() {
        val outside = tmp.newFolder("outside")
        val precious = File(outside, "precious.txt").apply { writeText("keep me") }
        val tree = tmp.newFolder("tree")
        File(tree, "bin").mkdirs()
        File(tree, "bin/bash").writeText("x")
        Files.createSymbolicLink(File(tree, "storage").toPath(), outside.toPath())
        Files.createSymbolicLink(File(tree, "bin/dangling").toPath(), File("/nonexistent").toPath())

        BootstrapInstaller.deleteTree(tree)

        assertFalse(tree.exists())
        assertTrue(precious.isFile)
    }

    @Test
    fun `deleteTree is a no-op for a missing directory`() {
        BootstrapInstaller.deleteTree(File(tmp.root, "missing"))
    }
}
