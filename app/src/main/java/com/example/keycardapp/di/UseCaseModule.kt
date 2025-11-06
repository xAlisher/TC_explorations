package com.example.keycardapp.di

import com.example.keycardapp.domain.repository.KeycardRepository
import com.example.keycardapp.domain.usecase.ReadVcFromNdefUseCase
import com.example.keycardapp.domain.usecase.ValidateVcUseCase
import com.example.keycardapp.domain.usecase.VerifyPinUseCase
import com.example.keycardapp.domain.usecase.VerifyVcProofUseCase
import com.example.keycardapp.domain.usecase.WriteUrlUseCase
import com.example.keycardapp.domain.usecase.WriteVcUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {
    
    @Provides
    @Singleton
    fun provideVerifyPinUseCase(
        keycardRepository: KeycardRepository
    ): VerifyPinUseCase {
        return VerifyPinUseCase(keycardRepository)
    }
    
    @Provides
    @Singleton
    fun provideWriteUrlUseCase(
        keycardRepository: KeycardRepository
    ): WriteUrlUseCase {
        return WriteUrlUseCase(keycardRepository)
    }
    
    @Provides
    @Singleton
    fun provideValidateVcUseCase(): ValidateVcUseCase {
        return ValidateVcUseCase()
    }
    
    @Provides
    @Singleton
    fun provideWriteVcUseCase(
        keycardRepository: KeycardRepository,
        validateVcUseCase: ValidateVcUseCase
    ): WriteVcUseCase {
        return WriteVcUseCase(keycardRepository, validateVcUseCase)
    }
    
    @Provides
    @Singleton
    fun provideReadVcFromNdefUseCase(): ReadVcFromNdefUseCase {
        return ReadVcFromNdefUseCase()
    }
    
    @Provides
    @Singleton
    fun provideVerifyVcProofUseCase(): VerifyVcProofUseCase {
        return VerifyVcProofUseCase()
    }
}

