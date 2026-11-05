package com.subhajit.mulberry.auth

/** The ways into a Mulberry account. One account can have both. */
enum class SignInMethod(val raw: String) {
    GOOGLE("google"),
    APPLE("apple");

    companion object {
        fun fromRaw(raw: String): SignInMethod? = entries.firstOrNull { it.raw == raw }
    }
}
