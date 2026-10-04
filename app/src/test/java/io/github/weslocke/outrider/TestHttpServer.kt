package io.github.weslocke.outrider

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny HTTP/1.1 server on plain sockets for the client tests (Android's unit-test classpath has no
 * com.sun.net.httpserver). One request per connection; routes by path; records what each path received.
 */
class TestHttpServer : AutoCloseable {
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    class Reply(val status: Int, val body: ByteArray = ByteArray(0), val headers: List<Pair<String, String>> = emptyList()) {
        constructor(status: Int, body: String, vararg headers: Pair<String, String>) : this(status, body.toByteArray(Charsets.UTF_8), headers.toList())
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val routes = ConcurrentHashMap<String, (Request) -> Reply>()
    val seen = ConcurrentHashMap<String, Request>()
    val port: Int get() = socket.localPort

    init {
        Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break
                }
                Thread { serve(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    fun route(path: String, handler: (Request) -> Reply) {
        routes[path] = handler
    }

    private fun serve(client: Socket) = client.use { c ->
        val input = BufferedInputStream(c.getInputStream())
        fun line(): String {
            val b = ByteArrayOutputStream()
            while (true) {
                val x = input.read()
                if (x < 0 || x == '\n'.code) break
                if (x != '\r'.code) b.write(x)
            }
            return b.toString(Charsets.ISO_8859_1.name())
        }
        val (method, target) = line().split(' ').let { it[0] to it.getOrElse(1) { "/" } }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = line()
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length).also { var read = 0; while (read < length) { val n = input.read(it, read, length - read); if (n < 0) break; read += n } }
        val path = target.substringBefore('?')
        val request = Request(method, path, headers, body)
        seen[path] = request
        val reply = routes[path]?.invoke(request) ?: Reply(404, "no route")
        val out = c.getOutputStream()
        val head = StringBuilder("HTTP/1.1 ${reply.status} X\r\nContent-Length: ${reply.body.size}\r\nConnection: close\r\n")
        for ((k, v) in reply.headers) head.append("$k: $v\r\n")
        out.write(head.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
        out.write(reply.body)
        out.flush()
    }

    override fun close() = socket.close()
}
