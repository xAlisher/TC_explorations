package com.example.keycardapp.domain.model

/**
 * Enum representing available use cases.
 */
enum class UseCase {
    WRITE_URL_TO_NDEF,
    WRITE_VC_TO_NDEF,
    READ_VC_FROM_NDEF,
    SIGN_DATA_AND_WRITE_TO_NDEF,
    READ_SIGNED_DATA_FROM_NDEF
}

