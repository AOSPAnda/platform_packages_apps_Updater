/*
 * SPDX-FileCopyrightText: 2017 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.libremobileos.updater.misc

import android.os.SystemProperties

object BuildInfoUtils {
    @JvmStatic
    fun getBuildVersion(): String = SystemProperties.get(Constants.PROP_BUILD_VERSION)
}