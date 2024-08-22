/*
 * SPDX-FileCopyrightText: 2017-2022 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.libremobileos.updater.download

import java.io.File
import java.io.IOException

interface DownloadClient {
    /**
     * Start the download. This method has no effect if the download already started.
     */
    fun start()

    /**
     * Resume the download. The download will fail if the server can't fulfil the
     * partial content request and DownloadCallback.onFailure() will be called.
     * This method has no effect if the download already started or the destination
     * file doesn't exist.
     */
    fun resume()

    /**
     * Cancel the download. This method has no effect if the download isn't ongoing.
     */
    fun cancel()

    interface DownloadCallback {
        fun onResponse(headers: Headers)
        fun onSuccess()
        fun onFailure(cancelled: Boolean)
    }

    fun interface ProgressListener {
        fun update(bytesRead: Long, contentLength: Long, speed: Long, eta: Long)
    }

    interface Headers {
        operator fun get(name: String): String?
    }

    class Builder {
        private var url: String? = null
        private var destination: File? = null
        private var callback: DownloadCallback? = null
        private var progressListener: ProgressListener? = null
        private var useDuplicateLinks: Boolean = false

        @Throws(IOException::class)
        fun build(): DownloadClient {
            checkNotNull(url) { "No download URL defined" }
            checkNotNull(destination) { "No download destination defined" }
            checkNotNull(callback) { "No download callback defined" }
            return HttpURLConnectionClient(
                url!!,
                destination!!,
                progressListener,
                callback!!,
                useDuplicateLinks
            )
        }

        fun setUrl(url: String) = apply { this.url = url }

        fun setDestination(destination: File) = apply { this.destination = destination }

        fun setDownloadCallback(downloadCallback: DownloadCallback) =
            apply { this.callback = downloadCallback }

        fun setProgressListener(progressListener: ProgressListener) =
            apply { this.progressListener = progressListener }

        fun setUseDuplicateLinks(useDuplicateLinks: Boolean) =
            apply { this.useDuplicateLinks = useDuplicateLinks }
    }
}