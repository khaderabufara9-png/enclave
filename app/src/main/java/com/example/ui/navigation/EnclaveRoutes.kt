package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed interface EnclaveRoute {
    @Serializable data object Splash : EnclaveRoute
    @Serializable data object Welcome : EnclaveRoute
    @Serializable data object OnboardingPhilosophy : EnclaveRoute
    @Serializable data object ProfileCreation : EnclaveRoute
    @Serializable data object MainHome : EnclaveRoute
    @Serializable data class Chat(val conversationId: String, val relationshipId: String) : EnclaveRoute
    @Serializable data object QrInvitation : EnclaveRoute
    @Serializable data object QrScanner : EnclaveRoute
    @Serializable data object IncomingCall : EnclaveRoute
    @Serializable data object OutgoingCall : EnclaveRoute
    @Serializable data object AudioCall : EnclaveRoute
    @Serializable data object VideoCall : EnclaveRoute
    @Serializable data object GroupCall : EnclaveRoute
    @Serializable data object SpacesManagement : EnclaveRoute
    @Serializable data object SecretSpaceAccess : EnclaveRoute
    @Serializable data object ProfileEditor : EnclaveRoute
    @Serializable data class ContactSettings(val relationshipId: String) : EnclaveRoute
    @Serializable data object PrivacySettings : EnclaveRoute
    @Serializable data object SecuritySettings : EnclaveRoute
    @Serializable data object NotificationSettings : EnclaveRoute
    @Serializable data object Diagnostics : EnclaveRoute
    @Serializable data object AboutSupport : EnclaveRoute
}

enum class HomeTab(val routeKey: String) {
    CONVERSATIONS("conversations"),
    CONTACTS("contacts"),
    CALLS("calls"),
    SPACES("spaces"),
    SETTINGS("settings"),
}
