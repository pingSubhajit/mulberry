package com.subhajit.mulberry.home

object CanvasProcessDefaults {
    @Volatile
    private var appliedColdStartDefaults = false

    fun shouldForceNoToolOnColdStart(): Boolean {
        if (appliedColdStartDefaults) return false
        appliedColdStartDefaults = true
        return true
    }
}

