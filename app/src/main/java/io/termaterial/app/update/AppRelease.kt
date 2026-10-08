package io.termaterial.app.update

import org.json.JSONArray
import org.json.JSONObject

/** One published build of the app: a GitHub release created by the CI workflow. */
data class AppRelease(
    val tag: String,
    val versionName: String,
    /** The CI run number, which is also the APK's versionCode (see app/build.gradle.kts). */
    val versionCode: Int,
    /** Changes since the previous release, one entry per item (see [ReleaseParser.changelogOf]). */
    val changelog: List<String>,
    val apkUrl: String,
    /** In bytes, or -1 if unknown. */
    val apkSize: Long,
    /** Lowercase hex SHA-256 of the APK, when GitHub provides one (the asset's `digest`). */
    val apkSha256: String?,
)

/** What an update brings: every release newer than the installed version, newest first. */
data class UpdateOffer(val releases: List<AppRelease>) {
    init {
        require(releases.isNotEmpty()) { "an update offer needs at least one release" }
    }

    /** The release that would be installed. */
    val latest: AppRelease get() = releases.first()
}

/** Reads the CI's releases from GitHub's `GET /repos/{owner}/{repo}/releases` JSON. */
object ReleaseParser {

    /**
     * Written by the CI right after the changelog in each release's notes (see
     * .github/workflows/build.yml): what comes after it is boilerplate, not meant for the app.
     */
    const val CHANGELOG_END_MARKER = "<!-- termaterial:changelog-end -->"

    /**
     * Every installable release in [json]: drafts and prereleases, releases without an APK asset
     * and tags without a version code (e.g. the old one-release-per-branch "debug-<branch>" ones)
     * are skipped.
     */
    fun parseReleases(json: String): List<AppRelease> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { parseRelease(array.getJSONObject(it)) }
    }

    private fun parseRelease(release: JSONObject): AppRelease? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.stringOrNull("tag_name") ?: return null
        val versionCode = versionCodeOf(tag) ?: return null
        val assets = release.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.stringOrNull("name")?.endsWith(".apk") == true }
            ?: return null
        val url = apk.stringOrNull("browser_download_url")?.takeIf { it.startsWith("https://") } ?: return null
        return AppRelease(
            tag = tag,
            versionName = tag.removePrefix("v"),
            versionCode = versionCode,
            changelog = changelogOf(release.stringOrNull("body").orEmpty()),
            apkUrl = url,
            apkSize = apk.optLong("size", -1),
            apkSha256 = apk.stringOrNull("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
        )
    }

    /** The number ending a tag such as `v0.2.0-debug.31` - the CI run number, i.e. the versionCode. */
    fun versionCodeOf(tag: String): Int? = Regex("""(\d+)$""").find(tag)?.value?.toIntOrNull()

    /**
     * The list items of the notes' changelog part, before [CHANGELOG_END_MARKER] - nothing for
     * notes without the marker, which predate the changelog and only hold boilerplate.
     */
    fun changelogOf(body: String): List<String> {
        if (!body.contains(CHANGELOG_END_MARKER)) return emptyList()
        return body.substringBefore(CHANGELOG_END_MARKER)
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.substring(2).trim() }
            .filter { it.isNotEmpty() }
    }

    /** The releases newer than [installedVersionCode], newest first, or null if there are none. */
    fun offerFor(releases: List<AppRelease>, installedVersionCode: Int): UpdateOffer? =
        releases
            .filter { it.versionCode > installedVersionCode }
            .sortedByDescending { it.versionCode }
            .distinctBy { it.versionCode }
            .takeIf { it.isNotEmpty() }
            ?.let(::UpdateOffer)

    /** optString() turns a JSON null into the string "null"; this does not. */
    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
}
