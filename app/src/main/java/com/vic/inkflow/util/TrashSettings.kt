package com.vic.inkflow.util

import android.content.Context
import com.vic.inkflow.data.TrashRetention

/**
 * Trash retention preference. Stored in the same `inkflow_settings` prefs file as
 * every other user setting; the value is normalised on read so a stale or
 * hand-edited value can never shorten retention by accident.
 */
object TrashSettings {
    private const val PREFS = "inkflow_settings"
    const val KEY_RETENTION_DAYS = "trash_retention_days"

    fun retentionDays(context: Context): Int {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_RETENTION_DAYS, TrashRetention.DEFAULT_DAYS)
        return TrashRetention.normalizedDays(raw)
    }

    fun setRetentionDays(context: Context, days: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RETENTION_DAYS, TrashRetention.normalizedDays(days))
            .apply()
    }
}
