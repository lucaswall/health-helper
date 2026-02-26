package com.healthhelper.app.di

import android.content.Context
import com.healthhelper.app.data.HealthConnectStatusProvider
import com.healthhelper.app.data.HealthConnectStatusProviderImpl
import com.healthhelper.app.data.repository.HealthConnectRepositoryImpl
import com.healthhelper.app.domain.repository.HealthConnectRepository
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
    fun provideHealthConnectStatusProvider(
        @ApplicationContext context: Context,
    ): HealthConnectStatusProvider {
        return HealthConnectStatusProviderImpl(context)
    }

    @Provides
    @Singleton
    fun provideHealthConnectRepository(
        @ApplicationContext context: Context,
        statusProvider: HealthConnectStatusProvider,
    ): HealthConnectRepository {
        return HealthConnectRepositoryImpl(context, statusProvider)
    }
}
