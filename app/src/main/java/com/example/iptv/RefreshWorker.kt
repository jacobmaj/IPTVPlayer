package com.example.iptv

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Descarga las listas de madrugada para que la app abra rápido durante el día. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = Prefs(applicationContext).load()
        val hasData = if (s.type == SourceType.M3U) s.m3uUrl.isNotBlank() else s.server.isNotBlank() && s.user.isNotBlank()
        if (!hasData) return Result.success()
        return try {
            buildSource(s, applicationContext.cacheDir).prefetch()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
