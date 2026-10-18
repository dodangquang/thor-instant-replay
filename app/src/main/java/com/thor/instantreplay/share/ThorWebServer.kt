package com.thor.instantreplay.share

import android.os.Environment
import android.util.Log
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class ThorWebServer(private val port: Int = 8080, private val english: Boolean = false) {
    private fun t(vi: String, en: String) = if (english) en else vi

    companion object {
        private const val TAG = "ThorWebServer"

        fun getLocalIpAddress(): String? {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (intf in interfaces) {
                    val addrs = intf.inetAddresses
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: continue
                            // Prioritize local network IP (e.g. 192.168.x.x, 10.x.x.x, 172.16-31.x.x)
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                return ip
                            }
                        }
                    }
                }
                // Fallback to any non-loopback IPv4
                val interfacesFallback = NetworkInterface.getNetworkInterfaces()
                for (intf in interfacesFallback) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving IP", e)
            }
            return null
        }
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var serverThread: Thread? = null

    fun start(): Boolean {
        if (isRunning.get()) return true
        return try {
            serverSocket = ServerSocket(port)
            isRunning.set(true)
            serverThread = Thread({ listenLoop() }, "ThorWebServerThread").apply { start() }
            Log.i(TAG, "ThorWebServer started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ThorWebServer on port $port", e)
            false
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        Log.i(TAG, "ThorWebServer stopped")
    }

    fun isServerRunning(): Boolean = isRunning.get()

    private fun listenLoop() {
        val server = serverSocket ?: return
        while (isRunning.get() && !server.isClosed) {
            try {
                val clientSocket = server.accept()
                Thread({ handleClient(clientSocket) }, "ThorClientHandler").start()
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = BufferedOutputStream(s.getOutputStream())

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val fullPath = parts[1]

                // Read headers
                val headers = mutableMapOf<String, String>()
                var headerLine = reader.readLine()
                while (!headerLine.isNullOrEmpty()) {
                    val idx = headerLine.indexOf(":")
                    if (idx > 0) {
                        headers[headerLine.substring(0, idx).trim().lowercase()] =
                            headerLine.substring(idx + 1).trim()
                    }
                    headerLine = reader.readLine()
                }

                if (method.equals("GET", ignoreCase = true)) {
                    val uri = fullPath.substringBefore("?")
                    val query = fullPath.substringAfter("?", "")

                    when {
                        uri == "/" || uri == "/index.html" -> sendHtmlDashboard(out)
                        uri == "/video" || uri == "/download" -> {
                            val fileName = getQueryParam(query, "file")
                            val isDownload = uri == "/download"
                            if (!fileName.isNullOrEmpty()) {
                                sendVideoFile(fileName, isDownload, headers["range"], out)
                            } else {
                                send404(out)
                            }
                        }
                        else -> send404(out)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling client request: ${e.message}")
        }
    }

    private fun getQueryParam(query: String, key: String): String? {
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                if (k == key) {
                    return URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                }
            }
        }
        return null
    }

    private fun getClipsDir(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "ThorClips"
        )
    }

    private fun sendHtmlDashboard(out: OutputStream) {
        val clipsDir = getClipsDir()
        val clips = clipsDir.listFiles { f -> ClipFilePolicy.resolve(clipsDir, f.name) != null }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>")
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>")
        sb.append("<title>Android Replay</title>")
        sb.append("<style>")
        sb.append("body { background: #10151b; color: #e1e3e6; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; }")
        sb.append(".header { display: flex; align-items: center; justify-content: space-between; padding-bottom: 16px; border-bottom: 2px solid #dcff30; margin-bottom: 24px; }")
        sb.append(".logo { font-size: 22px; font-weight: 800; letter-spacing: 1px; color: #fff; }")
        sb.append(".logo span { color: #dcff30; }")
        sb.append(".badge { background: #dcff30; color: #000; font-weight: bold; font-size: 11px; padding: 4px 8px; border-radius: 4px; }")
        sb.append(".clip-card { background: #1b202c; border: 1px solid #29313f; border-radius: 10px; padding: 16px; margin-bottom: 14px; display: flex; align-items: center; justify-content: space-between; }")
        sb.append(".clip-info { display: flex; flex-direction: column; }")
        sb.append(".clip-name { font-size: 16px; font-weight: 600; color: #fff; margin-bottom: 6px; }")
        sb.append(".clip-meta { font-size: 13px; color: #8e9297; }")
        sb.append(".btn-group { display: flex; gap: 8px; }")
        sb.append(".btn { padding: 8px 16px; border-radius: 6px; font-size: 13px; font-weight: bold; text-decoration: none; cursor: pointer; border: none; display: inline-flex; align-items: center; }")
        sb.append(".btn-play { background: #29313f; color: #dcff30; border: 1px solid #dcff30; }")
        sb.append(".btn-dl { background: #dcff30; color: #000; }")
        sb.append(".btn:hover { opacity: 0.85; }")
        sb.append(".empty { text-align: center; color: #8e9297; padding: 40px; }")
        sb.append("video { width: 100%; max-height: 480px; border-radius: 8px; background: #000; margin-top: 10px; }")
        sb.append("</style></head><body>")

        sb.append("<div class='header'>")
        sb.append("<div class='logo'><span>ANDROID</span> REPLAY</div>")
        sb.append("<div class='badge'>AYN THOR • WI-FI SHARE</div>")
        sb.append("</div>")

        if (clips.isEmpty()) {
            sb.append("<div class='empty'>" + t("Chưa có clip đã lưu.", "No saved clips yet.") + "</div>")
        } else {
            sb.append("<div>")
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            for (clip in clips) {
                val sizeMb = String.format(Locale.US, "%.1f MB", clip.length() / (1024.0 * 1024.0))
                val timeStr = dateFormat.format(Date(clip.lastModified()))
                val safeName = ClipFilePolicy.queryName(clip.name)
                val displayName = ClipFilePolicy.htmlName(clip.name)

                sb.append("<div class='clip-card'>")
                sb.append("<div class='clip-info'>")
                sb.append("<div class='clip-name'>${displayName}</div>")
                sb.append("<div class='clip-meta'>$sizeMb · $timeStr</div>")
                sb.append("</div>")
                sb.append("<div class='btn-group'>")
                sb.append("<a class='btn btn-play' href='/video?file=${safeName}' target='_blank'>▶ ${t("Xem", "Watch")}</a>")
                sb.append("<a class='btn btn-dl' href='/download?file=${safeName}'>⬇ ${t("Tải về", "Download")}</a>")
                sb.append("</div>")
                sb.append("</div>")
            }
            sb.append("</div>")
        }

        sb.append("</body></html>")

        val htmlBytes = sb.toString().toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: ${htmlBytes.size}\r\n" +
                "Connection: close\r\n\r\n"

        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(htmlBytes)
        out.flush()
    }

    private fun sendVideoFile(
        fileName: String,
        isDownload: Boolean,
        rangeHeader: String?,
        out: OutputStream
    ) {
        val file = ClipFilePolicy.resolve(getClipsDir(), fileName)
        if (file == null) {
            send404(out)
            return
        }

        val fileLength = file.length()
        var start: Long = 0
        var end: Long = fileLength - 1
        var isPartial = false

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            isPartial = true
            val ranges = rangeHeader.substring(6).split("-")
            try {
                if (ranges[0].isNotEmpty()) start = ranges[0].toLong()
                if (ranges.size > 1 && ranges[1].isNotEmpty()) end = ranges[1].toLong()
            } catch (_: Exception) {}
            if (end >= fileLength) end = fileLength - 1
        }

        val contentLength = end - start + 1
        val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"

        val headerName = ClipFilePolicy.dispositionName(file.name)
        val disposition = if (isDownload) {
            "Content-Disposition: attachment; filename=\"${headerName}\"\r\n"
        } else {
            "Content-Disposition: inline; filename=\"${headerName}\"\r\n"
        }

        val rangeResponse = if (isPartial) {
            "Content-Range: bytes $start-$end/$fileLength\r\n"
        } else ""

        val headers = statusLine +
                "Content-Type: video/mp4\r\n" +
                "Accept-Ranges: bytes\r\n" +
                disposition +
                rangeResponse +
                "Content-Length: $contentLength\r\n" +
                "Connection: close\r\n\r\n"

        out.write(headers.toByteArray(Charsets.UTF_8))

        // Stream file contents
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(64 * 1024)
            var bytesRemaining = contentLength
            while (bytesRemaining > 0) {
                val toRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read == -1) break
                out.write(buffer, 0, read)
                bytesRemaining -= read
            }
        }
        out.flush()
    }

    private fun send404(out: OutputStream) {
        val body = "404 Not Found".toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }
}
