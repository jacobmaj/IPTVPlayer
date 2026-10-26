package com.example.iptv

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Configura la carga de carátulas (poca RAM, caché en disco) y la actualización nocturna de listas. */
class IptvApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        scheduleNightlyRefresh()
    }

    // Todos los días hacia las 4:00, si hay red sin límite de datos (Wi-Fi o cable)
    private fun scheduleNightlyRefresh() {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance()
        target.set(Calendar.HOUR_OF_DAY, 4)
        target.set(Calendar.MINUTE, 0)
        target.set(Calendar.SECOND, 0)
        if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        val delayMin = (target.timeInMillis - now.timeInMillis) / 60_000

        val request = PeriodicWorkRequestBuilder<RefreshWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setInitialDelay(delayMin, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("nightly-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }   // máx. 12 % de la RAM de la app
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(150L * 1024 * 1024).build() }
            .allowRgb565(true)          // imágenes sin transparencia usan la mitad de memoria
            .respectCacheHeaders(false) // guarda las carátulas aunque el servidor diga que no
            .crossfade(false)
            .build()
}
