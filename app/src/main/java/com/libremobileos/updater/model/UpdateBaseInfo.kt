/*
 * SPDX-FileCopyrightText: 2017 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.libremobileos.updater.model

interface UpdateBaseInfo {
    val name: String
    val downloadId: String
    val timestamp: Long
    val type: String
    val version: String
    val downloadUrl: String
    val fileSize: Long
}