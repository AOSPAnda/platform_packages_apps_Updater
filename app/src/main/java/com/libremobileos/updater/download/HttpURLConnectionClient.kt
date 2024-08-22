/*
 * SPDX-FileCopyrightText: 2017-2022 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.libremobileos.updater.download

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpURLConnectionClient(
    url: String,
    private val destination: File,
    private val progressListener: DownloadClient.ProgressListener?,
    private val callback: DownloadClient.DownloadCallback,
    private val useDuplicateLinks: Boolean
) : DownloadClient {

    private var client: HttpURLConnection = URL(url).openConnection() as HttpURLConnection
    private var downloadThread: DownloadThread? = null

    companion object {
        private const val TAG = "HttpURLConnectionClient"

        private fun isSuccessCode(statusCode: Int) = statusCode / 100 == 2
        private fun isRedirectCode(statusCode: Int) = statusCode / 100 == 3
        private fun isPartialContentCode(statusCode: Int) = statusCode == 206
    }

    override fun start() {
        if (downloadThread != null) {
            Log.e(TAG, "Already downloading")
            return
        }
        downloadFileInternalCommon(false)
    }

    override fun resume() {
        if (downloadThread != null) {
            Log.e(TAG, "Already downloading")
            return
        }
        downloadFileResumeInternal()
    }

    override fun cancel() {
        downloadThread?.let {
            it.interrupt()
            downloadThread = null
        } ?: Log.e(TAG, "Not downloading")
    }

    private fun downloadFileResumeInternal() {
        if (!destination.exists()) {
            callback.onFailure(false)
            return
        }
        val offset = destination.length()
        client.setRequestProperty("Range", "bytes=$offset-")
        downloadFileInternalCommon(true)
    }

    private fun downloadFileInternalCommon(resume: Boolean) {
        if (downloadThread != null) {
            Log.wtf(TAG, "Already downloading")
            return
        }

        downloadThread = DownloadThread(resume).also { it.start() }
    }

    inner class Headers : DownloadClient.Headers {
        override fun get(name: String): String? = client.getHeaderField(name)
    }

    private inner class DownloadThread(private val resume: Boolean) : Thread() {
        private var totalBytes: Long = 0
        private var totalBytesRead: Long = 0
        private var curSampleBytes: Long = 0
        private var lastMillis: Long = 0
        private var speed: Long = -1
        private var eta: Long = -1

        private fun calculateSpeed(justResumed: Boolean) {
            val millis = SystemClock.elapsedRealtime()
            if (justResumed) {
                lastMillis = millis
                speed = -1
                curSampleBytes = totalBytesRead
                return
            }
            val delta = millis - lastMillis
            if (delta > 500) {
                val curSpeed = ((totalBytesRead - curSampleBytes) * 1000) / delta
                speed = if (speed == -1L) curSpeed else ((speed * 3) + curSpeed) / 4
                lastMillis = millis
                curSampleBytes = totalBytesRead
            }
        }

        private fun calculateEta() {
            if (speed > 0) {
                eta = (totalBytes - totalBytesRead) / speed
            }
        }

        @Throws(IOException::class)
        private fun changeClientUrl(newUrl: URL) {
            val range = client.getRequestProperty("Range")
            client.disconnect()
            client = newUrl.openConnection() as HttpURLConnection
            range?.let { client.setRequestProperty("Range", it) }
        }

        @Throws(IOException::class)
        private fun handleDuplicateLinks() {
            val protocol = client.url.protocol

            data class DuplicateLink(val url: String, val priority: Int)

            val duplicates = client.headerFields.entries
                .find { it.key.equals("Link", ignoreCase = true) }
                ?.value
                ?.mapNotNull { field ->
                    val regex = "(?i)<(.+)>\\s*;\\s*rel=duplicate(?:.*pri=([0-9]+).*|.*)?".toRegex()
                    regex.find(field)?.let { match ->
                        val url = match.groupValues[1]
                        val priority = match.groupValues[2].toIntOrNull() ?: 999999
                        DuplicateLink(url, priority)
                    }
                }
                ?.sortedBy { it.priority }
                ?.toMutableList()

            var newUrl = client.getHeaderField("Location")
            while (true) {
                try {
                    val url = URL(newUrl)
                    if (url.protocol != protocol) {
                        throw IOException("Protocol changes are not allowed")
                    }
                    Log.d(TAG, "Downloading from $newUrl")
                    changeClientUrl(url)
                    client.connectTimeout = 5000
                    client.connect()
                    if (!isSuccessCode(client.responseCode)) {
                        throw IOException("Server replied with ${client.responseCode}")
                    }
                    return
                } catch (e: IOException) {
                    if (!duplicates.isNullOrEmpty()) {
                        val link = duplicates.removeFirstOrNull()
                        if (link != null) {
                            newUrl = link.url
                            Log.e(TAG, "Using duplicate link ${link.url}", e)
                        } else {
                            throw e
                        }
                    } else {
                        throw e
                    }
                }
            }
        }

        override fun run() {
            var justResumed = false
            try {
                client.instanceFollowRedirects = !useDuplicateLinks
                client.connect()
                var responseCode = client.responseCode

                if (useDuplicateLinks && isRedirectCode(responseCode)) {
                    handleDuplicateLinks()
                    responseCode = client.responseCode
                }

                callback.onResponse(Headers())

                if (resume && isPartialContentCode(responseCode)) {
                    justResumed = true
                    totalBytesRead = destination.length()
                    Log.d(TAG, "The server fulfilled the partial content request")
                } else if (resume || !isSuccessCode(responseCode)) {
                    Log.e(TAG, "The server replied with code $responseCode")
                    callback.onFailure(isInterrupted)
                    return
                }

                client.inputStream.use { inputStream ->
                    FileOutputStream(destination, resume).use { outputStream ->
                        totalBytes = client.contentLength + totalBytesRead
                        val buffer = ByteArray(8192)
                        var count: Int
                        while (inputStream.read(buffer)
                                .also { count = it } != -1 && !isInterrupted
                        ) {
                            outputStream.write(buffer, 0, count)
                            totalBytesRead += count.toLong()
                            calculateSpeed(justResumed)
                            calculateEta()
                            justResumed = false
                            progressListener?.update(totalBytesRead, totalBytes, speed, eta)
                        }
                        progressListener?.update(totalBytesRead, totalBytes, speed, eta)

                        outputStream.flush()

                        if (isInterrupted) {
                            callback.onFailure(true)
                        } else {
                            callback.onSuccess()
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error downloading file", e)
                callback.onFailure(isInterrupted)
            } finally {
                client.disconnect()
            }
        }
    }
}