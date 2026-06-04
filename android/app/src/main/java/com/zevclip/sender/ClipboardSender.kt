package com.zevclip.sender

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL

sealed interface SendResult {
    data class Success(val message: String) : SendResult
    data class Failure(
        val message: String,
        val retryableWithDiscovery: Boolean = false
    ) : SendResult
}

sealed interface PullResult {
    data class Success(val text: String) : PullResult
    data object Empty : PullResult
    data class Failure(
        val message: String,
        val retryableWithDiscovery: Boolean = false
    ) : PullResult
}

object ClipboardSender {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_RESPONSE_PREVIEW = 200

    fun send(ipAddress: String, port: Int, text: String, pairingToken: String): SendResult {
        var connection: HttpURLConnection? = null

        return try {
            val activeConnection = URL("http://$ipAddress:$port/clipboard")
                .openConnection() as HttpURLConnection
            connection = activeConnection

            val body = text.toByteArray(Charsets.UTF_8)

            activeConnection.requestMethod = "POST"
            activeConnection.connectTimeout = CONNECT_TIMEOUT_MS
            activeConnection.readTimeout = READ_TIMEOUT_MS
            activeConnection.doOutput = true
            activeConnection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            activeConnection.setRequestProperty("X-ZevClip-Token", pairingToken)
            activeConnection.setFixedLengthStreamingMode(body.size)

            activeConnection.outputStream.use { output ->
                output.write(body)
            }

            val responseCode = activeConnection.responseCode
            val responseBody = readResponseBody(activeConnection, responseCode)

            if (responseCode in 200..299) {
                SendResult.Success("Sent successfully (HTTP $responseCode).")
            } else if (responseCode == 401) {
                SendResult.Failure("Pairing token rejected (HTTP 401). Check the token shown on the Mac.")
            } else {
                val detail = responseBody.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                SendResult.Failure("Mac receiver returned HTTP $responseCode$detail")
            }
        } catch (_: SocketTimeoutException) {
            SendResult.Failure(
                "Connection timed out. Check the Mac IP, port, and Wi-Fi network.",
                retryableWithDiscovery = true
            )
        } catch (_: ConnectException) {
            SendResult.Failure(
                "Connection refused. Confirm the Mac receiver is running on port $port.",
                retryableWithDiscovery = true
            )
        } catch (_: NoRouteToHostException) {
            SendResult.Failure(
                "Mac is unreachable. Confirm both devices are on the same local network.",
                retryableWithDiscovery = true
            )
        } catch (error: IOException) {
            SendResult.Failure(
                "Network error: ${error.message ?: "request failed"}",
                retryableWithDiscovery = true
            )
        } catch (error: SecurityException) {
            SendResult.Failure("Android blocked the request: ${error.message ?: "permission denied"}")
        } finally {
            connection?.disconnect()
        }
    }

    fun pull(ipAddress: String, port: Int, pairingToken: String): PullResult {
        var connection: HttpURLConnection? = null

        return try {
            val activeConnection = URL("http://$ipAddress:$port/clipboard")
                .openConnection() as HttpURLConnection
            connection = activeConnection

            activeConnection.requestMethod = "GET"
            activeConnection.connectTimeout = CONNECT_TIMEOUT_MS
            activeConnection.readTimeout = READ_TIMEOUT_MS
            activeConnection.setRequestProperty("X-ZevClip-Token", pairingToken)

            when (val responseCode = activeConnection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val text = readResponseBody(activeConnection, responseCode, trim = false)
                    PullResult.Success(text)
                }
                HttpURLConnection.HTTP_NO_CONTENT -> PullResult.Empty
                HttpURLConnection.HTTP_UNAUTHORIZED ->
                    PullResult.Failure("Pairing token mismatch.")
                else -> {
                    val responseBody = readResponseBody(activeConnection, responseCode)
                    val detail = responseBody.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
                    PullResult.Failure("Mac receiver returned HTTP $responseCode$detail")
                }
            }
        } catch (_: SocketTimeoutException) {
            PullResult.Failure(
                "Connection timed out. Check the Mac IP, port, and Wi-Fi network.",
                retryableWithDiscovery = true
            )
        } catch (_: ConnectException) {
            PullResult.Failure(
                "Connection refused. Confirm the Mac receiver is running on port $port.",
                retryableWithDiscovery = true
            )
        } catch (_: NoRouteToHostException) {
            PullResult.Failure(
                "Mac is unreachable. Confirm both devices are on the same local network.",
                retryableWithDiscovery = true
            )
        } catch (error: IOException) {
            PullResult.Failure(
                "Network error: ${error.message ?: "request failed"}",
                retryableWithDiscovery = true
            )
        } catch (error: SecurityException) {
            PullResult.Failure("Android blocked the clipboard pull: ${error.message ?: "permission denied"}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readResponseBody(
        connection: HttpURLConnection,
        responseCode: Int,
        trim: Boolean = true
    ): String {
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""

        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val text = reader.readText()
            if (trim) {
                text.trim().take(MAX_RESPONSE_PREVIEW)
            } else {
                text
            }
        }
    }
}
