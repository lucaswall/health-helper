package com.healthhelper.app.di

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import com.healthhelper.app.data.repository.HealthConnectRepository
import com.healthhelper.app.data.repository.HealthConnectRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideHealthConnectClient(
        @ApplicationContext context: Context,
    ): HealthConnectClient {
        return HealthConnectClient.getOrCreate(context)
    }

    @Provides
    @Singleton
    fun provideHealthConnectRepository(
        client: HealthConnectClient,
    ): HealthConnectRepository {
        return HealthConnectRepositoryImpl(client)
    }
}
