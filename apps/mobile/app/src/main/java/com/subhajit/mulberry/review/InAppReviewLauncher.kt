package com.subhajit.mulberry.review

import android.util.Log
import androidx.activity.ComponentActivity
import com.google.android.play.core.review.ReviewManagerFactory

object InAppReviewLauncher {
    data class Diagnostics(
        val installerPackageName: String?,
        val activityClassName: String
    )

    data class AttemptResult(
        val diagnostics: Diagnostics,
        val result: Result<Unit>
    )

    suspend fun launch(activity: ComponentActivity): Result<Unit> = runCatching {
        val manager = ReviewManagerFactory.create(activity)
        val reviewInfo = manager.requestReviewFlow().await()
        manager.launchReviewFlow(activity, reviewInfo).await()
    }

    suspend fun launchWithDiagnostics(activity: ComponentActivity): AttemptResult {
        val diagnostics = Diagnostics(
            installerPackageName = runCatching {
                activity.packageManager.getInstallerPackageName(activity.packageName)
            }.getOrNull(),
            activityClassName = activity::class.java.name
        )

        val result = launch(activity)
        Log.i(
            "MulberryInAppReview",
            "In-app review attempted installer=${diagnostics.installerPackageName} " +
                "activity=${diagnostics.activityClassName} success=${result.isSuccess} " +
                "error=${result.exceptionOrNull()?.message}"
        )
        return AttemptResult(diagnostics = diagnostics, result = result)
    }
}
