/*
 * SPDX-FileCopyrightText: 2017 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.libremobileos.updater.model

open class UpdateBase(
    override var name: String = "",
    override var downloadUrl: String = "",
    override var downloadId: String = "",
    override var timestamp: Long = 0,
    override var type: String = "",
    override var version: String = "",
    override var fileSize: Long = 0
) : UpdateBaseInfo