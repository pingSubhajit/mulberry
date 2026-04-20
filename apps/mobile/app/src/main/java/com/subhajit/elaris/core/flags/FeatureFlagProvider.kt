package com.subhajit.elaris.core.flags

import kotlinx.coroutines.flow.Flow

interface FeatureFlagProvider {
    val flags: Flow<FeatureFlags>

    suspend fun setOverride(flag: FeatureFlag, enabled: Boolean?)

    suspend fun clearOverrides()
}
