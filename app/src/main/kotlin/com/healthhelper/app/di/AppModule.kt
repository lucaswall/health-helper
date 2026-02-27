package com.healthhelper.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.health.connect.client.HealthConnectClient
import androidx.work.WorkManager
import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.data.repository.DataStoreSettingsRepository
import com.healthhelper.app.data.repository.FoodScannerFoodLogRepository
import com.healthhelper.app.data.repository.HealthConnectNutritionRepository
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.repository.FoodLogRepository
import com.healthhelper.app.domain.repository.NutritionRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore

    @Provides
    @Singleton
    fun provideSettingsRepository(
        dataStore: DataStore<Preferences>,
    ): SettingsRepository = DataStoreSettingsRepository(dataStore)

    @Provides
    @Singleton
    fun provideHealthConnectClient(@ApplicationContext context: Context): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }

    @Provides
    @Singleton
    fun provideNutritionRepository(
        healthConnectClient: HealthConnectClient?,
    ): NutritionRepository = HealthConnectNutritionRepository(healthConnectClient)

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    @Provides
    @Singleton
    fun provideFoodScannerApiClient(httpClient: HttpClient): FoodScannerApiClient =
        FoodScannerApiClient(httpClient)

    @Provides
    @Singleton
    fun provideFoodLogRepository(
        apiClient: FoodScannerApiClient,
    ): FoodLogRepository = FoodScannerFoodLogRepository(apiClient)

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideSyncScheduler(workManager: WorkManager): SyncScheduler =
        SyncScheduler(workManager)
}
