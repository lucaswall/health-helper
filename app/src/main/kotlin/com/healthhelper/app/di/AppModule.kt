package com.healthhelper.app.di

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.health.connect.client.HealthConnectClient
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.WorkManager
import com.healthhelper.app.data.api.AnthropicApiClient
import com.healthhelper.app.data.api.FoodScannerApiClient
import com.healthhelper.app.data.repository.DataStoreSettingsRepository
import com.healthhelper.app.data.repository.FoodScannerFoodLogRepository
import com.healthhelper.app.data.repository.FoodScannerHealthRepositoryImpl
import com.healthhelper.app.data.repository.HealthConnectBloodGlucoseRepository
import com.healthhelper.app.data.repository.HealthConnectBloodPressureRepository
import com.healthhelper.app.data.repository.HealthConnectHeartRateRepository
import com.healthhelper.app.data.repository.DataStoreWaistReadingRepository
import com.healthhelper.app.data.repository.HealthConnectHydrationRepository
import com.healthhelper.app.data.repository.HealthConnectNutritionRepository
import com.healthhelper.app.data.repository.HealthConnectPermissionChecker
import com.healthhelper.app.data.sync.SyncScheduler
import com.healthhelper.app.domain.repository.BloodGlucoseRepository
import com.healthhelper.app.domain.repository.BloodPressureRepository
import com.healthhelper.app.domain.repository.HeartRateRepository
import com.healthhelper.app.domain.repository.WaistReadingRepository
import com.healthhelper.app.domain.repository.FoodLogRepository
import com.healthhelper.app.domain.repository.FoodScannerHealthRepository
import com.healthhelper.app.domain.repository.HealthPermissionChecker
import com.healthhelper.app.domain.repository.HydrationRepository
import com.healthhelper.app.domain.repository.NutritionRepository
import com.healthhelper.app.domain.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    // A corrupt settings file would otherwise make every read throw CorruptionException
    // (crashing un-guarded collectors). Replacing it resets sync watermarks -> full re-sync.
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Timber.e(e, "Settings DataStore corrupted, replacing with empty preferences")
        emptyPreferences()
    },
)

private const val ENCRYPTED_PREFS_NAME = "encrypted_settings"
private const val WAIST_DATASTORE_NAME = "waist_readings"

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

    /**
     * Its own file, not the settings store: settings may be reset to empty on corruption (a
     * re-sync recovers them), but pending waist readings exist nowhere else, so a corrupt file is
     * copied aside before the store starts empty.
     */
    @Provides
    @Singleton
    @WaistDataStore
    fun provideWaistDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        val file = context.preferencesDataStoreFile(WAIST_DATASTORE_NAME)
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { e ->
                val copy = preserveCorruptFile(file, System.currentTimeMillis())
                Timber.e("Waist DataStore corrupted (%s); preserved copy=%b", e::class.simpleName, copy != null)
                emptyPreferences()
            },
            produceFile = { file },
        )
    }

    /** Copies a corrupt DataStore file next to itself; returns the copy, or null if it couldn't be made. */
    internal fun preserveCorruptFile(file: java.io.File, nowMs: Long): java.io.File? = try {
        if (file.exists()) {
            java.io.File(file.parentFile, "${file.name}.corrupt-$nowMs").also { file.copyTo(it, overwrite = false) }
        } else {
            null
        }
    } catch (e: java.io.IOException) {
        null
    }

    @Provides
    @Singleton
    fun provideWaistReadingRepository(
        @WaistDataStore dataStore: DataStore<Preferences>,
    ): WaistReadingRepository = DataStoreWaistReadingRepository(dataStore)

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
        @ApplicationContext context: Context,
    ): BloodPressureRepository = HealthConnectBloodPressureRepository(healthConnectClient, context)

    @Provides
    @Singleton
    fun provideHeartRateRepository(
        healthConnectClient: HealthConnectClient?,
    ): HeartRateRepository = HealthConnectHeartRateRepository(healthConnectClient)

    @Provides
    @Singleton
    fun provideBloodGlucoseRepository(
        healthConnectClient: HealthConnectClient?,
        @ApplicationContext context: Context,
    ): BloodGlucoseRepository = HealthConnectBloodGlucoseRepository(healthConnectClient, context)

    @Provides
    @Singleton
    fun provideHydrationRepository(
        healthConnectClient: HealthConnectClient?,
    ): HydrationRepository = HealthConnectHydrationRepository(healthConnectClient)

    @Provides
    @Singleton
    fun provideHealthPermissionChecker(
        healthConnectClient: HealthConnectClient?,
    ): HealthPermissionChecker = HealthConnectPermissionChecker(healthConnectClient)

    @Provides
    @Singleton
    // OkHttp engine: honors Android Network Security Config, supports TLS 1.3 / HTTP/2,
    // pools connections and uses the platform proxy (CIO does none of these).
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000L
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = 30_000L
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
    fun provideFoodScannerHealthRepository(
        apiClient: FoodScannerApiClient,
        settingsRepository: SettingsRepository,
    ): FoodScannerHealthRepository = FoodScannerHealthRepositoryImpl(apiClient, settingsRepository)

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

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    internal fun createEncryptedSharedPreferences(context: Context): SharedPreferences? =
        createEncryptedSharedPreferences(context) { ctx ->
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

    /**
     * Creates the encrypted prefs. If creation fails with a crypto/keyset error (e.g. the Tink
     * keyset no longer matches the Keystore master key), the unreadable prefs file is deleted and
     * creation retried; only if that also fails is the master key alias deleted and creation
     * retried once more. Stored secrets are lost on recovery, but they were unreadable anyway.
     * Non-crypto failures return null without deleting anything.
     */
    internal fun createEncryptedSharedPreferences(
        context: Context,
        deletePrefs: (Context) -> Unit = { it.deleteSharedPreferences(ENCRYPTED_PREFS_NAME) },
        deleteMasterKey: () -> Unit = ::deleteMasterKeyAlias,
        creator: (Context) -> SharedPreferences,
    ): SharedPreferences? {
        val firstError = try {
            return creator(context)
        } catch (e: Exception) {
            e
        }
        if (!isRecoverableCryptoFailure(firstError)) {
            Timber.e(firstError, "Keystore unavailable, encrypted prefs not available")
            return null
        }
        Timber.e(firstError, "Encrypted prefs unreadable, deleting %s and retrying", ENCRYPTED_PREFS_NAME)
        runCatching { deletePrefs(context) }
            .onFailure { Timber.w(it, "Failed to delete encrypted prefs file") }
        try {
            return creator(context)
        } catch (e: Exception) {
            Timber.e(e, "Encrypted prefs retry failed, deleting master key and retrying")
        }
        runCatching {
            deleteMasterKey()
            deletePrefs(context)
        }.onFailure { Timber.w(it, "Failed to delete master key") }
        return try {
            creator(context)
        } catch (e: Exception) {
            Timber.e(e, "Keystore unavailable after recovery, encrypted prefs not available")
            null
        }
    }

    private fun isRecoverableCryptoFailure(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is GeneralSecurityException ||
                current is ProviderException ||
                current.javaClass.simpleName == "InvalidProtocolBufferException"
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun deleteMasterKeyAlias() {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
    }
}
