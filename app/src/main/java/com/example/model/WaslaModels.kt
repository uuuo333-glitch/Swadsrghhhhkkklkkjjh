package com.example.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class SocialPlatform(
    val id: String,
    val arabicLabel: String,
    val englishLabel: String,
    val packageName: String,
    val webFallbackUrl: String
) {
    WHATSAPP(
        id = "whatsapp",
        arabicLabel = "واتساب",
        englishLabel = "WhatsApp",
        packageName = "com.whatsapp",
        webFallbackUrl = "https://wa.me/"
    ),
    INSTAGRAM(
        id = "instagram",
        arabicLabel = "إنستقرام",
        englishLabel = "Instagram",
        packageName = "com.instagram.android",
        webFallbackUrl = "https://www.instagram.com/"
    ),
    FACEBOOK(
        id = "facebook",
        arabicLabel = "فيسبوك",
        englishLabel = "Facebook",
        packageName = "com.facebook.orca",
        webFallbackUrl = "https://www.facebook.com/messages/"
    ),
    SNAPCHAT(
        id = "snapchat",
        arabicLabel = "سناب شات",
        englishLabel = "Snapchat",
        packageName = "com.snapchat.android",
        webFallbackUrl = "https://www.snapchat.com/"
    );

    companion object {
        fun fromId(id: String): SocialPlatform =
            entries.firstOrNull { it.id == id } ?: WHATSAPP
    }
}

enum class DeliveryStatus(val id: String) {
    SENT("sent"),
    DELIVERED("delivered"),
    READ("read"),
    SHIELDED_HELD("shielded_held"),
    QUEUED_OFFLINE("queued_offline");

    companion object {
        fun fromId(id: String): DeliveryStatus =
            entries.firstOrNull { it.id == id } ?: SENT
    }
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String? = null,
    val photoUrl: String? = null,
    val stealthDefaultEnabled: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SocialAccount(
    @DocumentId val id: String = "",
    val userId: String = "",
    val platform: String = SocialPlatform.WHATSAPP.id,
    val handle: String = "",
    val displayName: String = "",
    val isConnected: Boolean = false,
    val autoShieldNewChats: Boolean = false,
    val hideReadReceipts: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val platformEnum: SocialPlatform
        get() = SocialPlatform.fromId(platform)
}

@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val userId: String = "",
    val contactName: String = "",
    val contactHandle: String = "",
    val platform: String = SocialPlatform.WHATSAPP.id,
    val lastMessage: String = "",
    val unreadCount: Long = 0L,
    val shieldedCount: Long = 0L,
    val isInternetCutOff: Boolean = false,
    val silentReadOnly: Boolean = true,
    val appearOnlineToContact: Boolean = true,
    val isPinned: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val platformEnum: SocialPlatform
        get() = SocialPlatform.fromId(platform)
}

@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val conversationId: String = "",
    val text: String = "",
    val isOutgoing: Boolean = true,
    val deliveryStatus: String = DeliveryStatus.SENT.id,
    val productId: String? = null,
    val productTitle: String? = null,
    val productPrice: String? = null,
    val productImageKey: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    val statusEnum: DeliveryStatus
        get() = DeliveryStatus.fromId(deliveryStatus)

    val hasProductCard: Boolean
        get() = !productTitle.isNullOrBlank()
}

@IgnoreExtraProperties
data class CatalogItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val description: String = "",
    val price: Double = 0.0,
    val currency: String = "ر.س",
    val category: String = "عام",
    val presetImageKey: String = "watch",
    val inStock: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
