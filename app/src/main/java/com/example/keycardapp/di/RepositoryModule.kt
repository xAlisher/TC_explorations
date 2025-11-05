package com.example.keycardapp.di

import com.example.keycardapp.data.repository.KeycardRepositoryImpl
import com.example.keycardapp.domain.repository.KeycardRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindKeycardRepository(
        keycardRepositoryImpl: KeycardRepositoryImpl
    ): KeycardRepository
}

