package com.example.keycardapp.viewmodel

import androidx.lifecycle.ViewModel
import com.example.keycardapp.domain.model.UseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * ViewModel for managing use case navigation and coordination.
 */
@HiltViewModel
class UseCaseViewModel @Inject constructor() : ViewModel() {
    
    private val _currentUseCase = MutableStateFlow<UseCase?>(null)
    val currentUseCase: StateFlow<UseCase?> = _currentUseCase.asStateFlow()
    
    /**
     * Navigate to a use case.
     */
    fun navigateToUseCase(useCase: UseCase) {
        _currentUseCase.value = useCase
    }
    
    /**
     * Navigate back to use case list.
     */
    fun navigateBack() {
        _currentUseCase.value = null
    }
}


