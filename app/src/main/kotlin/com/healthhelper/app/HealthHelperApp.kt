package com.healthhelper.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.timber.SentryTimberTree
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class HealthHelperApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("HealthHelperApp: started (debug build)")
        }

        // Plant Sentry tree unconditionally — forwards Timber.w/e as breadcrumbs/events
        Timber.plant(
            SentryTimberTree(
                scopes = Sentry.getCurrentScopes(),
                minEventLevel = SentryLevel.ERROR,
                minBreadcrumbLevel = SentryLevel.INFO,
            ),
        )

        Sentry.configureScope { scope ->
            scope.setTag("environment", if (BuildConfig.DEBUG) "debug" else "release")
        }
    }
}
