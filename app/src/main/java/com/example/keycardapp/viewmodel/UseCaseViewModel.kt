package com.example.keycardapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.keycardapp.domain.repository.KeycardRepository
import com.example.keycardapp.domain.model.UseCase
import com.example.keycardapp.domain.usecase.ValidateVcUseCase
import com.example.keycardapp.domain.usecase.VerifyPinUseCase
import com.example.keycardapp.domain.usecase.WriteUrlUseCase
import com.example.keycardapp.domain.usecase.WriteVcUseCase
import com.example.keycardapp.data.repository.KeycardRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ViewModel for managing use case navigation and coordination.
 */
class UseCaseViewModel(
    pairingPassword: String
) : ViewModel() {
    
    // Initialize repository and use cases
    private val keycardRepository: KeycardRepository = KeycardRepositoryImpl()
    private val verifyPinUseCase = VerifyPinUseCase(keycardRepository)
    private val writeUrlUseCase = WriteUrlUseCase(keycardRepository)
    private val validateVcUseCase = ValidateVcUseCase()
    private val writeVcUseCase = WriteVcUseCase(keycardRepository, validateVcUseCase)
    
    // ViewModels for each use case
    val writeUrlViewModel = WriteUrlViewModel(verifyPinUseCase, writeUrlUseCase, pairingPassword)
    val writeVcViewModel = WriteVcViewModel(verifyPinUseCase, writeVcUseCase, pairingPassword)
    
    private val _currentUseCase = MutableStateFlow<UseCase?>(null)
    val currentUseCase: StateFlow<UseCase?> = _currentUseCase.asStateFlow()
    
    /**
     * Navigate to a use case.
     */
    fun navigateToUseCase(useCase: UseCase) {
        _currentUseCase.value = useCase
        
        when (useCase) {
            UseCase.WRITE_URL_TO_NDEF -> {
                writeUrlViewModel.reset()
                writeUrlViewModel.showPinDialog()
            }
            UseCase.WRITE_VC_TO_NDEF -> {
                writeVcViewModel.reset()
                writeVcViewModel.showPinDialog()
            }
            else -> {
                // Coming soon
            }
        }
    }
    
    /**
     * Navigate back to use case list.
     */
    fun navigateBack() {
        _currentUseCase.value = null
        writeUrlViewModel.reset()
        writeVcViewModel.reset()
    }
}


