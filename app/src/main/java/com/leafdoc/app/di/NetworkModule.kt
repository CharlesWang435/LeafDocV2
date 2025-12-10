package com.leafdoc.app.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.leafdoc.app.BuildConfig
import com.leafdoc.app.data.remote.DiagnosisApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://api.plant.id/v3/"
    private const val TIMEOUT_SECONDS = 60L
    private const val MAX_RETRIES = 3

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .create()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Api-Key", BuildConfig.PLANT_ID_API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "LeafDoc-Android/${BuildConfig.VERSION_NAME}")
                    .build()

                var lastException: Exception? = null
                var response: okhttp3.Response? = null

                // Retry logic for 5xx server errors
                repeat(MAX_RETRIES) { attempt ->
                    try {
                        response = chain.proceed(request)
                        if (response!!.isSuccessful || response!!.code < 500) {
                            return@addInterceptor response!!
                        }
                        response?.close()
                        if (attempt < MAX_RETRIES - 1) {
                            Thread.sleep(1000L * (attempt + 1))
                        }
                    } catch (e: Exception) {
                        lastException = e
                        if (attempt < MAX_RETRIES - 1) {
                            Thread.sleep(1000L * (attempt + 1))
                        }
                    }
                }

                response ?: throw (lastException ?: java.io.IOException("Request failed after $MAX_RETRIES retries"))
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideDiagnosisApiService(retrofit: Retrofit): DiagnosisApiService {
        return retrofit.create(DiagnosisApiService::class.java)
    }
}
