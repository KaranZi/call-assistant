package com.karan.callassistant

/**
 * Turns a call transcript into a few suggested follow-up messages.
 *
 * PHASE 0 (this spike): offline heuristic drafts, so the pop-up always has something to show.
 *
 * PHASE 3: call an LLM over its HTTPS API (Anthropic or OpenAI) with the transcript text and
 * ask for 2-3 context-aware follow-ups. Note this sends the transcript *text* off-device to
 * the chosen provider (audio still never leaves the phone). Keep the key out of source — read
 * it from a local secret / BuildConfig field the user supplies, never hardcode it.
 */
object DraftGenerator {

    /** A follow-up suggestion the user can send as-is or edit. */
    data class Draft(val title: String, val body: String)

    fun fromTranscript(transcript: String): List<Draft> {
        // Heuristic placeholder until the LLM path is wired.
        return listOf(
            Draft(
                "Quick recap",
                "Thanks for the call. To recap what we agreed: "
            ),
            Draft(
                "Follow-up / action",
                "Following up on our call — I'll "
            ),
            Draft(
                "Thanks",
                "Great talking just now, thanks for your time!"
            )
        )
    }

    fun sampleDrafts(): List<Draft> = fromTranscript("")
}
