/*
 * SPDX-FileCopyrightText: 2017 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.libremobileos.updater.model

import java.io.File

interface UpdateInfo : UpdateBaseInfo {
    val status: UpdateStatus
    val persistentStatus: Int
    val file: File
    override val fileSize: Long
    val progress: Int
    val eta: Long
    val speed: Long
    val installProgress: Int
    val isAvailableOnline: Boolean
    val isFinalizing: Boolean
}