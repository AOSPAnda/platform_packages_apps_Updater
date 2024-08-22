/*
 * SPDX-FileCopyrightText: 2017-2022 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.libremobileos.updater.misc

import android.content.Context
import android.content.res.Resources
import com.libremobileos.updater.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object StringGenerator {
    @JvmStatic
    fun getDateLocalizedUTC(context: Context?, dateFormat: Int, unixTimestamp: Long): String {
        val f = DateFormat.getDateInstance(dateFormat, getCurrentLocale(context))
        f.timeZone = TimeZone.getTimeZone("UTC")
        val date = Date(unixTimestamp * 1000)
        return f.format(date)
    }

    @JvmStatic
    fun bytesToMegabytes(context: Context?, bytes: Long): String {
        return String.format(getCurrentLocale(context), "%.0f", bytes / 1024f / 1024f)
    }

    @JvmStatic
    fun formatETA(context: Context?, millis: Long): String {
        val SECOND_IN_MILLIS = 1000L
        val MINUTE_IN_MILLIS = SECOND_IN_MILLIS * 60
        val HOUR_IN_MILLIS = MINUTE_IN_MILLIS * 60
        val res: Resources = context?.resources ?: Resources.getSystem()
        return when {
            millis >= HOUR_IN_MILLIS -> {
                val hours = ((millis + 1800000) / HOUR_IN_MILLIS).toInt()
                res.getQuantityString(R.plurals.eta_hours, hours, hours)
            }

            millis >= MINUTE_IN_MILLIS -> {
                val minutes = ((millis + 30000) / MINUTE_IN_MILLIS).toInt()
                res.getQuantityString(R.plurals.eta_minutes, minutes, minutes)
            }

            else -> {
                val seconds = ((millis + 500) / SECOND_IN_MILLIS).toInt()
                res.getQuantityString(R.plurals.eta_seconds, seconds, seconds)
            }
        }
    }

    @JvmStatic
    fun getCurrentLocale(context: Context?): Locale {
        return context?.resources?.configuration?.locales
            ?.getFirstMatch(context.resources.assets.locales)
            ?: Locale.getDefault()
    }
}