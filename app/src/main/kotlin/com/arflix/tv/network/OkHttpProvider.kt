package com.arflix.tv.network

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.arflix.tv.BuildConfig
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp clients: the API client (TMDB, feed; 50 MB disk cache) and a dedicated
 * image client for Coil. System DNS, preferring IPv4 for TMDB hosts.
 *
 * SSL/TLS validation is handled by NetworkSecurityConfig (res/xml/network_security_config.xml):
 * - Release: System certificates only (secure)
 * - Debug: User + System certificates (allows proxy debugging)
 *
 * DO NOT add custom TrustManager - it defeats certificate validation.
 */
object OkHttpProvider {
    private const val TAG = "AppHttp"
    /** 50 MB disk cache for API responses (TMDB metadata, feed). */
    private const val HTTP_CACHE_SIZE = 50L * 1024L * 1024L
    private const val IMAGE_DISK_CACHE_SIZE = 48L * 1024L * 1024L

    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Must be called once from Application.onCreate() before any network calls.
     * Provides the application context needed for the disk cache directory.
     */
    fun init(context: Context) {
        // `applicationContext` is null when called from Application.attachBaseContext — the
        // Application is not registered with the framework yet. That is precisely where this has
        // to run (Hilt builds Retrofit, and therefore this client, inside Application.onCreate's
        // super call), so fall back to the context we were handed: it is the Application itself.
        appContext = context.applicationContext ?: context
    }

    @Volatile
    internal var appContext: Context? = null

    private val clientLock = Any()
    private val appConnectionPool = ConnectionPool(16, 5, TimeUnit.MINUTES)

    @Volatile
    private var appClient: OkHttpClient? = null

    @Volatile
    private var appHttpCache: Cache? = null

    @Volatile
    private var coilSharedClient: OkHttpClient? = null

    /** System DNS, with IPv4 addresses first for TMDB hosts (IPv6 routes to TMDB are flaky). */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val resolved = Dns.SYSTEM.lookup(hostname)
            if (!hostname.contains("tmdb", ignoreCase = true) &&
                !hostname.contains("themoviedb", ignoreCase = true)
            ) {
                return resolved
            }
            val ipv4 = resolved.filterIsInstance<Inet4Address>()
            if (ipv4.isEmpty()) return resolved
            return ipv4 + resolved.filterNot { it is Inet4Address }
        }
    }

    val client: OkHttpClient
        get() = appClient ?: synchronized(clientLock) {
            appClient ?: buildAppClient().also { appClient = it }
        }

    private fun buildAppClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            redactHeader("Cookie")
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val builder = OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(appConnectionPool)
            .dns(dns)
            .retryOnConnectionFailure(true)

        appContext?.let { ctx ->
            builder.cache(getOrCreateHttpCache(ctx))
            // Never persist a failure. A transient 5xx or a rate-limit 429 carrying a cacheable
            // header would otherwise be replayed from disk for its whole stated lifetime, long
            // after the server recovered.
            builder.addNetworkInterceptor(noCacheErrorsInterceptor)
        } ?: Log.w(
            TAG,
            "API client built without app context — HTTP disk cache disabled for this process"
        )

        return builder.build()
    }

    /** Applies a browser UA unless the call set its own. */
    private val userAgentInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.header("User-Agent") != null) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header("User-Agent", DEFAULT_USER_AGENT).build())
        }
    }

    /** Marks unsuccessful responses `no-store` so the disk cache never keeps a failure. */
    private val noCacheErrorsInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.isSuccessful) {
            response
        } else {
            response.newBuilder().header("Cache-Control", "no-store").build()
        }
    }

    private fun getOrCreateHttpCache(context: Context): Cache {
        return appHttpCache ?: synchronized(clientLock) {
            appHttpCache ?: Cache(File(context.cacheDir, "http_cache"), HTTP_CACHE_SIZE).also {
                appHttpCache = it
            }
        }
    }

    val coilClient: OkHttpClient
        get() = coilSharedClient ?: synchronized(clientLock) {
            coilSharedClient ?: buildCoilClient().also { coilSharedClient = it }
        }

    private fun buildCoilClient(): OkHttpClient {
        // Dedicated image client with its own pool and short timeouts, so slow API calls never
        // starve poster loads and a dead image CDN fails fast instead of freezing the grid.
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
            .dns(dns)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun createCoilImageLoader(context: Context): ImageLoader {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        // Keep the image cache modest: TV boxes cap the heap at ~384MB. Disk cache still
        // holds 48MB, so posters reload quickly without pinning Java heap.
        val imageCacheBytes = if (activityManager?.isLowRamDevice == true) {
            24 * 1024 * 1024
        } else {
            32 * 1024 * 1024
        }
        return ImageLoader.Builder(context)
            .okHttpClient(coilClient)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(imageCacheBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(IMAGE_DISK_CACHE_SIZE)
                    .build()
            }
            .crossfade(false)
            .respectCacheHeaders(false)
            .allowRgb565(true)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()
    }
}
