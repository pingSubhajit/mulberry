package com.subhajit.mulberry.reactions

import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionAnnouncementTest {
    private fun batch(heart: Int = 0, hug: Int = 0, kiss: Int = 0, smile: Int = 0, laugh: Int = 0, sparkle: Int = 0) =
        PendingReactionBatch(
            pairSessionId = "pair",
            generation = 1L,
            heartCount = heart,
            hugCount = hug,
            kissCount = kiss,
            smileCount = smile,
            laughCount = laugh,
            sparkleCount = sparkle,
            receivedAtMs = 0L
        )

    @Test
    fun namesThePartnerAndCountsEachKindInTheRailsOrder() {
        assertEquals(
            "Sam sent 2 ❤️, 1 💋",
            reactionArrivalAnnouncement(batch(kiss = 1, heart = 2), partnerName = "Sam")
        )
        assertEquals(
            "Sam sent 1 🤗, 3 😂, 1 ✨",
            reactionArrivalAnnouncement(batch(sparkle = 1, laugh = 3, hug = 1), partnerName = " Sam ")
        )
    }

    @Test
    fun withoutANameItIsYourPartner() {
        assertEquals("Your partner sent 1 😊", reactionArrivalAnnouncement(batch(smile = 1), partnerName = null))
        assertEquals("Your partner sent 1 😊", reactionArrivalAnnouncement(batch(smile = 1), partnerName = "  "))
    }
}
