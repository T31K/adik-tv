package com.arflix.tv.di

import android.content.Context
import com.arflix.tv.data.api.TmdbApi
import com.arflix.tv.megaflix.MegaflixFeedApi
import com.arflix.tv.network.OkHttpProvider
import com.arflix.tv.util.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @JvmStatic
    fun provideOkHttpClient(): OkHttpClient = OkHttpProvider.client

    @Provides
    @Singleton
    @JvmStatic
    fun provideTmdbApi(okHttpClient: OkHttpClient, @ApplicationContext context: Context): TmdbApi {
        val tmdbClient = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val originalHttpUrl = original.url

                val langPrefs = context.getSharedPreferences("app_locale", Context.MODE_PRIVATE)
                val lang = langPrefs.getString("locale_tag", "en-US") ?: "en-US"

                // Inject the UI language unless it's English or the call site set one
                // explicitly (an explicit `language` must win, e.g. the English trailer
                // fallback). Map "iw" to "he".
                val urlBuilder = originalHttpUrl.newBuilder()
                if (lang != "en-US" && originalHttpUrl.queryParameter("language") == null) {
                    val tmdbLang = lang.replace("iw", "he").replace('_', '-')
                    urlBuilder.setQueryParameter("language", tmdbLang)
                }
                chain.proceed(original.newBuilder().url(urlBuilder.build()).build())
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(Constants.TMDB_BASE_URL)
            .client(tmdbClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }

    @Provides
    @Singleton
    @JvmStatic
    fun provideMegaflixFeedApi(okHttpClient: OkHttpClient): MegaflixFeedApi {
        return Retrofit.Builder()
            .baseUrl(Constants.MEGAFLIX_FEED_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MegaflixFeedApi::class.java)
    }

    @Provides
    @Singleton
    @JvmStatic
    fun provideStreamApi(okHttpClient: OkHttpClient): com.arflix.tv.stream.StreamApi {
        return Retrofit.Builder()
            .baseUrl(Constants.MEGAFLIX_FEED_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(com.arflix.tv.stream.StreamApi::class.java)
    }
}
