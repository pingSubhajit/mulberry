package com.subhajit.elaris.data.bootstrap

enum class PairingStatus(val displayName: String) {
    UNPAIRED("Unpaired"),
    PAIRING_PENDING("Pairing Pending"),
    PAIRED_PLACEHOLDER("Placeholder Paired")
}

enum class SessionDisplayState(val displayName: String) {
    EMPTY("Awaiting first session"),
    PLACEHOLDER_SESSION("Placeholder shared session")
}

data class SessionBootstrapState(
    val hasCompletedOnboarding: Boolean = false,
    val pairingStatus: PairingStatus = PairingStatus.UNPAIRED,
    val hasWallpaperConfigured: Boolean = false,
    val sessionDisplayState: SessionDisplayState = SessionDisplayState.EMPTY
)
