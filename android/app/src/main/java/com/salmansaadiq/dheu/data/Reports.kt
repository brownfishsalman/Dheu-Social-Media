package com.salmansaadiq.dheu.data

import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What can be reported (0025), as the website's src/lib/reports.ts. */
enum class ReportTarget(val db: String, val noun: String) {
    Post("post", "post"),
    Story("story", "story"),
    Comment("comment", "comment"),
    Profile("profile", "account"),
}

data class ReportReason(val value: String, val label: String)

/** Same reasons, same order, as the website; the database checks the list. */
val REPORT_REASONS = listOf(
    ReportReason("spam", "Spam or a scam"),
    ReportReason("harassment", "Bullying or harassment"),
    ReportReason("hate", "Hate speech or symbols"),
    ReportReason("nudity", "Nudity or sexual content"),
    ReportReason("violence", "Violence or threats"),
    ReportReason("self_harm", "Self-harm or suicide"),
    ReportReason("other", "Something else"),
)

const val REPORT_DETAILS_MAX = 500

object Reports {
    @Serializable
    private data class ReportInsert(
        @SerialName("reporter_id") val reporterId: String,
        @SerialName("target_type") val targetType: String,
        val reason: String,
        val details: String,
        @SerialName("post_id") val postId: String? = null,
        @SerialName("story_id") val storyId: String? = null,
        @SerialName("comment_id") val commentId: String? = null,
        @SerialName("target_user_id") val targetUserId: String? = null,
    )

    /**
     * Files a report about [id] (a post, story, comment, or a member for
     * [ReportTarget.Profile]); the database fills in whose it is. Returns
     * false when I already have an open report about it.
     */
    suspend fun report(me: String, target: ReportTarget, id: String, reason: String, details: String): Boolean {
        val row = ReportInsert(
            reporterId = me,
            targetType = target.db,
            reason = reason,
            details = details.trim().take(REPORT_DETAILS_MAX),
            postId = id.takeIf { target == ReportTarget.Post },
            storyId = id.takeIf { target == ReportTarget.Story },
            commentId = id.takeIf { target == ReportTarget.Comment },
            targetUserId = id.takeIf { target == ReportTarget.Profile },
        )
        return try {
            Backend.client.from("reports").insert(row)
            true
        } catch (e: Exception) {
            if (e.pgCode == "23505") false else throw e
        }
    }
}
