/*
 * SPDX-FileCopyrightText: 2017 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.libremobileos.updater.model

import java.io.File

class Update(
    name: String = "",
    downloadUrl: String = "",
    downloadId: String = "",
    timestamp: Long = 0,
    type: String = "",
    version: String = "",
    fileSize: Long = 0,
    override var status: UpdateStatus = UpdateStatus.UNKNOWN,
    override var persistentStatus: Int = UpdateStatus.Persistent.UNKNOWN,
    override var file: File = File(""),
    override var progress: Int = 0,
    override var eta: Long = 0,
    override var speed: Long = 0,
    override var installProgress: Int = 0,
    override var isAvailableOnline: Boolean = false,
    override var isFinalizing: Boolean = false
) : UpdateBase(name, downloadUrl, downloadId, timestamp, type, version, fileSize), UpdateInfo {

    constructor() : this(
        name = "",
        downloadUrl = "",
        downloadId = "",
        timestamp = 0,
        type = "",
        version = "",
        fileSize = 0
    )

    constructor(update: UpdateInfo) : this(
        name = update.name,
        downloadUrl = update.downloadUrl,
        downloadId = update.downloadId,
        timestamp = update.timestamp,
        type = update.type,
        version = update.version,
        fileSize = update.fileSize,
        status = update.status,
        persistentStatus = update.persistentStatus,
        file = update.file,
        progress = update.progress,
        eta = update.eta,
        speed = update.speed,
        installProgress = update.installProgress,
        isAvailableOnline = update.isAvailableOnline,
        isFinalizing = update.isFinalizing
    )
}