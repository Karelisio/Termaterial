package io.termaterial.shell

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

class HttpDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: ServerSocket
    private val payload = ByteArray(100_000) { (it % 251).toByte() }
    @Volatile private var lastUserAgent: String? = null

    private val base get() = "http://127.0.0.1:${server.localPort}"

    /** Just enough HTTP/1.1 for these tests: one request per connection. */
    @Before
    fun startServer() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                thread(isDaemon = true) { socket.use { serve(it) } }
            }
        }
    }

    private fun serve(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        val path = reader.readLine().split(" ")[1]
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("User-Agent:", ignoreCase = true)) lastUserAgent = header.substringAfter(":").trim()
        }
        val out = socket.getOutputStream()
        fun respond(status: String, headers: List<String> = emptyList(), body: ByteArray = ByteArray(0)) {
            val head = (listOf("HTTP/1.1 $status", "Content-Length: ${body.size}", "Connection: close") + headers)
                .joinToString("\r\n", postfix = "\r\n\r\n")
            out.write(head.toByteArray())
            out.write(body)
            out.flush()
        }
        when (path) {
            "/file.bin" -> respond("200 OK", body = payload)
            "/relative" -> respond("302 Found", listOf("Location: /file.bin"))
            "/absolute" -> respond("301 Moved Permanently", listOf("Location: $base/relative"))
            "/loop" -> respond("307 Temporary Redirect", listOf("Location: /loop"))
            "/text" -> respond("200 OK", body = "[{\"tag_name\":\"v1\"}]".toByteArray())
            else -> respond("404 Not Found")
        }
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun `downloads through absolute and relative redirects and returns the SHA-256`() = runBlocking {
        val dest = File(tmp.root, "out.bin")
        var lastProgress = 0L to 0L

        val sha256 = HttpDownload.download("$base/absolute", dest, mapOf("User-Agent" to "Termaterial-test")) { read, total ->
            lastProgress = read to total
        }

        assertArrayEquals(payload, dest.readBytes())
        val expected = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        assertEquals(expected, sha256)
        assertEquals(payload.size.toLong() to payload.size.toLong(), lastProgress)
        assertEquals("Termaterial-test", lastUserAgent)
    }

    @Test
    fun `reports the status of a failed request`() {
        val error = assertThrows(HttpDownload.HttpStatusException::class.java) {
            HttpDownload.getText("$base/missing")
        }
        assertEquals(404, error.code)
    }

    @Test
    fun `gives up on endless redirects`() {
        val error = assertThrows(IOException::class.java) {
            HttpDownload.getText("$base/loop")
        }
        assertTrue(error.message!!.contains("Too many redirects"))
    }

    @Test
    fun `reads a text body`() {
        assertEquals("[{\"tag_name\":\"v1\"}]", HttpDownload.getText("$base/text"))
    }
}
