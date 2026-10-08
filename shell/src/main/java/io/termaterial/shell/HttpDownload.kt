package io.termaterial.shell

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Minimal HTTPS client used for the Termux bootstrap and for app updates: plain
 * [HttpURLConnection], with redirects followed by hand (also relative ones, and across hosts -
 * GitHub release assets redirect to a separate download host, which HttpURLConnection's own
 * redirect handling refuses to follow when the scheme or port changes).
 */
object HttpDownload {

    /**
     * Downloads [url] to [destFile], reporting progress ([totalBytes] is -1 when the server does
     * not say), and returns the file's SHA-256 as lowercase hex. Stops with a
     * [kotlinx.coroutines.CancellationException] if the calling coroutine is cancelled.
     */
    suspend fun download(
        url: String,
        destFile: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: suspend (bytesRead: Long, totalBytes: Long) -> Unit,
    ): String {
        val connection = open(url, headers)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytesRead += read
                        onProgress(bytesRead, totalBytes)
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            connection.disconnect()
        }
    }

    /** GETs [url] and returns its body as UTF-8 text. */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val connection = open(url, headers)
        try {
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    /** Connects to [url], following redirects; throws unless the final answer is 200 OK. */
    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        var currentUrl = url
        var redirects = 0
        while (true) {
            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            connection.connect()

            val code = connection.responseCode
            if (code in HTTP_REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("Redirect from $currentUrl had no Location header")
                connection.disconnect()
                if (++redirects > MAX_REDIRECTS) {
                    throw IOException("Too many redirects while fetching $url")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw HttpStatusException(code, currentUrl)
            }
            return connection
        }
    }

    class HttpStatusException(val code: Int, url: String) : IOException("Unexpected HTTP $code from $url")

    private const val BUFFER_SIZE = 32 * 1024
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private val HTTP_REDIRECT_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307,
        308,
    )
}
