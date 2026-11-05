package com.subhajit.mulberry.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.subhajit.mulberry.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives Sign in with Apple's result from the backend's redirect, hands it to the waiting
 * request, and brings Mulberry back over the Custom Tab.
 */
@AndroidEntryPoint
class AppleSignInCallbackActivity : ComponentActivity() {
    @Inject lateinit var appleSignInCoordinator: AppleSignInCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(appleSignInCoordinator::onCallback)
        // Clearing to the top closes the Custom Tab, which sits above Mulberry in its task.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
