package com.subhajit.mulberry.whatsnew

import java.time.LocalDate

data class WhatsNewEntry(
    val versionName: String? = null,
    val releasedAt: LocalDate? = null,
    val title: String? = null,
    val heroImagePathOrUrl: String? = null,
    val ctaLabel: String = "Sounds awesome!",
    val markdownBody: String = ""
)

