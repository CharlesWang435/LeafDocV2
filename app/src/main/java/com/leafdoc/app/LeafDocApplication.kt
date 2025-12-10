package com.leafdoc.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class LeafDocApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initializeLogging()
    }

    private fun initializeLogging() {
        if (BuildConfig.DEBUG) {
            // Debug builds: full logging with class/method info
            Timber.plant(Timber.DebugTree())
        } else {
            // Release builds: log only errors and warnings
            Timber.plant(ReleaseTree())
        }
    }

    /**
     * A custom Timber tree for release builds that only logs errors and warnings.
     * In production, you would typically send these to a crash reporting service
     * like Firebase Crashlytics.
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Only log WARN and ERROR levels in release
            if (priority == android.util.Log.WARN || priority == android.util.Log.ERROR) {
                // In production, you could send to Crashlytics:
                // FirebaseCrashlytics.getInstance().log("$tag: $message")
                // t?.let { FirebaseCrashlytics.getInstance().recordException(it) }

                // For now, just use the default Android log
                android.util.Log.println(priority, tag ?: "LeafDoc", message)
            }
        }
    }
}
