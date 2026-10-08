package io.termaterial.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParserTest {

    private fun release(
        tag: String,
        body: String? = null,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assetName: String = "termaterial-${tag.removePrefix("v")}.apk",
        digest: String? = "sha256:ABCDEF",
    ): String {
        val bodyJson = body?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" } ?: "null"
        val digestJson = digest?.let { "\"$it\"" } ?: "null"
        return """
            {
              "tag_name": "$tag",
              "draft": $draft,
              "prerelease": $prerelease,
              "body": $bodyJson,
              "assets": [
                {
                  "name": "$assetName",
                  "size": 17149160,
                  "digest": $digestJson,
                  "browser_download_url": "https://github.com/o/r/releases/download/$tag/$assetName"
                }
              ]
            }
        """.trimIndent()
    }

    private val notes = """
        ### Nouveautés
        - Mise à jour intégrée avec changelog
        - Corrige le collage

        ${ReleaseParser.CHANGELOG_END_MARKER}
        APK debug généré automatiquement par la CI.
        - pas un changement
    """.trimIndent()

    @Test
    fun `parses an installable release`() {
        val releases = ReleaseParser.parseReleases("[${release("v0.2.0-debug.31", body = notes)}]")

        val parsed = releases.single()
        assertEquals("v0.2.0-debug.31", parsed.tag)
        assertEquals("0.2.0-debug.31", parsed.versionName)
        assertEquals(31, parsed.versionCode)
        assertEquals(listOf("Mise à jour intégrée avec changelog", "Corrige le collage"), parsed.changelog)
        assertEquals("https://github.com/o/r/releases/download/v0.2.0-debug.31/termaterial-0.2.0-debug.31.apk", parsed.apkUrl)
        assertEquals(17149160L, parsed.apkSize)
        assertEquals("abcdef", parsed.apkSha256)
    }

    @Test
    fun `skips drafts, prereleases, tags without a version code and releases without an APK`() {
        val json = listOf(
            release("v0.2.0-debug.40", draft = true),
            release("v0.2.0-debug.39", prerelease = true),
            release("debug-claude-termaterial-android-terminal-od4k0x"),
            release("v0.2.0-debug.38", assetName = "notes.txt"),
            release("v0.2.0-debug.37"),
        ).joinToString(",", "[", "]")

        assertEquals(listOf(37), ReleaseParser.parseReleases(json).map { it.versionCode })
    }

    @Test
    fun `notes without the marker or without a body have no changelog`() {
        val json = listOf(
            release("v0.2.0-debug.30", body = "APK debug généré automatiquement par la CI.\n- boilerplate"),
            release("v0.2.0-debug.29", body = null, digest = null),
        ).joinToString(",", "[", "]")

        val releases = ReleaseParser.parseReleases(json)
        assertEquals(listOf(emptyList<String>(), emptyList()), releases.map { it.changelog })
        assertNull(releases[1].apkSha256)
    }

    @Test
    fun `an offer lists every newer release, newest first, without duplicates`() {
        val releases = ReleaseParser.parseReleases(
            listOf(
                release("v0.2.0-debug.31"),
                release("v0.2.0-debug.33"),
                release("v0.2.0-debug.29"),
                release("v0.2.1-debug.33"),
                release("v0.2.0-debug.32"),
            ).joinToString(",", "[", "]")
        )

        val offer = ReleaseParser.offerFor(releases, installedVersionCode = 30)!!
        assertEquals(listOf(33, 32, 31), offer.releases.map { it.versionCode })
        assertEquals(33, offer.latest.versionCode)
    }

    @Test
    fun `no offer when the installed version is the newest`() {
        val releases = ReleaseParser.parseReleases("[${release("v0.2.0-debug.30")}]")
        assertNull(ReleaseParser.offerFor(releases, installedVersionCode = 30))
    }

    @Test
    fun `version code is the number ending the tag`() {
        assertEquals(31, ReleaseParser.versionCodeOf("v0.2.0-debug.31"))
        assertEquals(7, ReleaseParser.versionCodeOf("v1.0.7"))
        assertNull(ReleaseParser.versionCodeOf("debug-main"))
    }
}
