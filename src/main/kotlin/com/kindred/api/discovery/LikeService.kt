package com.kindred.api.discovery

import com.kindred.api.chat.Conversation
import com.kindred.api.chat.ConversationRepository
import com.kindred.api.notification.NotificationService
import com.kindred.api.photo.ModerationStatus
import com.kindred.api.photo.Photo
import com.kindred.api.photo.PhotoRepository
import com.kindred.api.profile.ProfileRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import kotlin.math.max
import kotlin.math.min

@Service
class LikeService(
    private val likes: LikeRepository,
    private val matches: MatchRepository,
    private val conversations: ConversationRepository,
    private val profiles: ProfileRepository,
    private val photos: PhotoRepository,
    private val notifications: NotificationService,
    private val clock: Clock,
    @param:Value("\${kindred.media.public-base-url}") private val publicBaseUrl: String,
) {

    /**
     * Like / superlike / pass. A mutual (super)like creates the match and its
     * conversation in the same transaction (§5). Superlikes signal, never boost.
     */
    @Transactional
    fun react(userId: Long, toUserId: Long, kind: LikeKind): ReactResponse {
        if (toUserId == userId) throw CannotReactToSelfException()
        if (!profiles.existsById(toUserId)) throw ReactionTargetNotFoundException()
        if (likes.findByFromUserAndToUser(userId, toUserId) != null) throw AlreadyReactedException()

        likes.save(Like(fromUser = userId, toUser = toUserId, kind = kind, createdAt = clock.instant()))
        if (kind == LikeKind.pass) return ReactResponse(matched = false)

        val reverse = likes.findByFromUserAndToUser(toUserId, userId)
        if (reverse == null || reverse.kind == LikeKind.pass) return ReactResponse(matched = false)

        val a = min(userId, toUserId)
        val b = max(userId, toUserId)
        if (matches.existsByUserAAndUserB(a, b)) return ReactResponse(matched = false)

        val match = matches.save(Match(userA = a, userB = b, createdAt = clock.instant()))
        val conversation = conversations.save(Conversation(matchId = requireNotNull(match.id), createdAt = clock.instant()))
        notifications.matchCreated(reactorId = userId, match = match, conversationId = requireNotNull(conversation.id))
        return ReactResponse(matched = true, matchId = match.id, conversationId = conversation.id)
    }

    @Transactional(readOnly = true)
    fun receivedLikes(userId: Long): List<ReceivedLikeResponse> {
        val received = likes.findUnansweredReceived(userId)
        if (received.isEmpty()) return emptyList()
        val ids = received.map { it.fromUser }
        val profilesById = profiles.findAllById(ids).associateBy { it.userId }
        val primaryPhotos = photos.findAllByProfileUserIdInAndModerationStatusAndIsPrimaryTrue(ids, ModerationStatus.approved)
            .associateBy(Photo::profileUserId)
        return received.mapNotNull { like ->
            val profile = profilesById[like.fromUser] ?: return@mapNotNull null
            ReceivedLikeResponse(
                userId = like.fromUser,
                displayName = profile.displayName,
                kind = like.kind,
                likedAt = like.createdAt,
                photo = PhotoSummary.from(primaryPhotos[like.fromUser], publicBaseUrl),
            )
        }
    }
}
