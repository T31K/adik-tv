package com.arflix.tv

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.util.DeviceType
import com.arflix.tv.util.DiagnosticsManager
import com.arflix.tv.util.detectDeviceType
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ADIK TV application: image loader, crash marker, profile bootstrap. Everything else
 * (feed sync, downloads) starts from Home once a profile is chosen.
 */
@HiltAndroidApp
class ArflixApplication : Application(), ImageLoaderFactory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var appImageLoader: ImageLoader? = null

    @Inject
    lateinit var profileManager: ProfileManager
    @Inject
    lateinit var watchlistRepository: WatchlistRepository

    /**
     * [OkHttpProvider.init] must run before *anything* first touches [OkHttpProvider.client]:
     * the client is built once, lazily, and keeps whatever cache it had at that moment. Hilt
     * injects this Application's fields inside `super.onCreate()`, so `attachBaseContext` is
     * the first callback where a context exists.
     */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        OkHttpProvider.init(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.arflix.tv.util.NavSound.init(this) // ADIK: preload UI hover sound

        DiagnosticsManager.initialize(this)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val prefs = getSharedPreferences("arvio_crash_store", android.content.Context.MODE_PRIVATE)
                val fallbackId = java.util.UUID.randomUUID().toString()
                prefs.edit()
                    .putString("last_crash_id", fallbackId)
                    .putString("last_crash_msg", "${throwable::class.java.simpleName}: ${throwable.message?.take(200) ?: ""}")
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .putString("last_crash_version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    .putBoolean("has_pending_crash_report", true)
                    .commit()
            } catch (_: Throwable) {
            } finally {
                defaultHandler?.uncaughtException(thread, throwable) ?: android.os.Process.killProcess(android.os.Process.myPid())
            }
        }

        // Initialize the active profile asynchronously to avoid blocking cold start.
        appScope.launch {
            runCatching { profileManager.initialize() }
            // Preload the local watchlist cache in the background.
            runCatching { watchlistRepository.getWatchlistItems() }
        }
    }

    override fun newImageLoader(): ImageLoader {
        val isTvDevice = detectDeviceType(this) == DeviceType.TV
        val isLowRamDevice = isLowRamDevice()
        return ImageLoader.Builder(this)
            // Dedicated Coil HTTP client (no logging interceptor, own connection pool).
            .okHttpClient(OkHttpProvider.coilClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    // Fixed TV image budget so low-RAM TVs don't drift into zram
                    // pressure while rows are being scrolled.
                    .maxSizeBytes(
                        when {
                            isTvDevice && isLowRamDevice -> 32 * 1024 * 1024
                            isTvDevice -> 48 * 1024 * 1024
                            else -> 64 * 1024 * 1024
                        }
                    )
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(if (isTvDevice) 128L * 1024L * 1024L else 96L * 1024L * 1024L)
                    .build()
            }
            .crossfade(false)
            .respectCacheHeaders(false)
            .allowRgb565(true)
            .bitmapConfig(Bitmap.Config.RGB_565)
            // No global placeholder (cards use their surface colour); failed loads are
            // transparent so the card background is the fallback visual.
            .error(android.R.color.transparent)
            .components {
                add(SvgDecoder.Factory())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
            .also { appImageLoader = it }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
        ) {
            clearImageMemoryCaches()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        clearImageMemoryCaches()
    }

    private fun clearImageMemoryCaches() {
        appImageLoader?.memoryCache?.clear()
        runCatching { imageLoader.memoryCache?.clear() }
    }

    private fun isLowRamDevice(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        return activityManager?.isLowRamDevice == true
    }

    companion object {
        lateinit var instance: ArflixApplication
            private set

        fun trimImageMemory() {
            if (::instance.isInitialized) {
                instance.clearImageMemoryCaches()
            }
        }
    }
}
