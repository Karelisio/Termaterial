package io.termaterial.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BootstrapArchTest {

    @Test
    fun `picks aarch64 for a 64-bit device that also lists 32-bit ABIs`() {
        val arch = BootstrapArch.forSupportedAbis(arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertEquals(BootstrapArch.ARM64_V8A, arch)
        assertEquals("aarch64", arch.termuxArch)
        assertEquals("bootstrap-aarch64.zip", arch.assetFileName)
    }

    @Test
    fun `picks arm for a 32-bit-only device`() {
        val arch = BootstrapArch.forSupportedAbis(arrayOf("armeabi-v7a", "armeabi"))
        assertEquals(BootstrapArch.ARMEABI_V7A, arch)
    }

    @Test
    fun `picks x86_64 over x86 when both are listed`() {
        val arch = BootstrapArch.forSupportedAbis(arrayOf("x86_64", "x86"))
        assertEquals(BootstrapArch.X86_64, arch)
    }

    @Test
    fun `throws for an unsupported device ABI list`() {
        assertThrows(UnsupportedOperationException::class.java) {
            BootstrapArch.forSupportedAbis(arrayOf("mips"))
        }
    }
}
