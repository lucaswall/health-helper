package com.healthhelper.app.di

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.health.connect.client.HealthConnectClient
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.WorkManager
import com.healthhelper.app.data.api.AnthropicApiClient
import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.data.repository.DataStoreSettingsRepository
import com.healthhelper.app.data.repository.FoodScannerFoodLogRepository
import com.healthhelper.app.data.repository.HealthConnectBloodPressureRepository
import com.healthhelper.app.data.repository.HealthConnectNutritionRepository
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.FoodLogRepository
import com.healthhelper.app.domain.repository.NutritionRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import timber.log.Timber
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
    fun provideEncryptedSharedPreferences(
        @ApplicationContext context: Context,
    ): SharedPreferences? = createEncryptedSharedPreferences(context)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        dataStore: DataStore<Preferences>,
        encryptedPrefs: SharedPreferences?,
    ): SettingsRepository = DataStoreSettingsRepository(dataStore, encryptedPrefs)

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
    fun provideBloodPressureRepository(
        healthConnectClient: HealthConnectClient?,
    ): BloodPressureRepository = HealthConnectBloodPressureRepository(healthConnectClient)

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000L
        }
    }

    @Provides
    @Singleton
    fun provideFoodScannerApiClient(httpClient: HttpClient): FoodScannerApiClient =
        FoodScannerApiClient(httpClient)

    @Provides
    @Singleton
    fun provideAnthropicApiClient(httpClient: HttpClient): AnthropicApiClient =
        AnthropicApiClient(httpClient)

    @Provides
    @Singleton
    fun provideFoodLogRepository(
        apiClient: FoodScannerApiClient,
        settingsRepository: SettingsRepository,
    ): FoodLogRepository = FoodScannerFoodLogRepository(apiClient, settingsRepository)

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideSyncScheduler(workManager: WorkManager): SyncScheduler =
        SyncScheduler(workManager)

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    internal fun createEncryptedSharedPreferences(context: Context): SharedPreferences? =
        createEncryptedSharedPreferences(context) { ctx ->
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                "encrypted_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

    internal fun createEncryptedSharedPreferences(
        context: Context,
        creator: (Context) -> SharedPreferences,
    ): SharedPreferences? = try {
        creator(context)
    } catch (e: Exception) {
        Timber.w(e, "Keystore unavailable, encrypted prefs not available")
        null
    }
}
