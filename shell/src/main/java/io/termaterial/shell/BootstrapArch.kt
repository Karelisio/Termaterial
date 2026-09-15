package io.termaterial.shell

/**
 * Maps an Android ABI (as reported by [android.os.Build.SUPPORTED_ABIS]) to the architecture
 * name used by termux-packages bootstrap release assets (`bootstrap-<arch>.zip`).
 */
enum class BootstrapArch(val androidAbi: String, val termuxArch: String) {
    ARM64_V8A("arm64-v8a", "aarch64"),
    ARMEABI_V7A("armeabi-v7a", "arm"),
    X86_64("x86_64", "x86_64"),
    X86("x86", "i686");

    val assetFileName: String get() = "bootstrap-$termuxArch.zip"

    companion object {
        /**
         * Picks the best bootstrap architecture for this device, honouring
         * [android.os.Build.SUPPORTED_ABIS] preference order (a 64-bit device that also lists a
         * 32-bit ABI for compatibility should still get the 64-bit bootstrap).
         */
        fun forSupportedAbis(supportedAbis: Array<String>): BootstrapArch {
            for (abi in supportedAbis) {
                entries.firstOrNull { it.androidAbi == abi }?.let { return it }
            }
            throw UnsupportedOperationException(
                "No Termux bootstrap available for supported ABIs: ${supportedAbis.joinToString()}"
            )
        }
    }
}
