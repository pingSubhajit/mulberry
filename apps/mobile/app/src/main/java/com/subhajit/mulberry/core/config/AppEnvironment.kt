package com.subhajit.mulberry.core.config

enum class AppEnvironment(val wireValue: String, val displayName: String) {
    DEV("dev", "Development"),
    PROD("prod", "Production");

    companion object {
        fun fromRaw(rawValue: String): AppEnvironment =
            entries.firstOrNull { it.wireValue.equals(rawValue.trim(), ignoreCase = true) } ?: PROD
    }
}
