package com.example.data

import android.content.Context
import com.example.R
import com.example.model.CatalogItem
import com.example.model.ChatMessage
import com.example.model.Conversation
import com.example.model.DeliveryStatus
import com.example.model.SocialAccount
import com.example.model.SocialPlatform
import com.example.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Locale

class WaslaRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun userDoc(uid: String) = db.collection("users").document(uid)

    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        val docRef = userDoc(userId)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    snapshot.toObject(UserProfile::class.java, ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = error.unwrapFirestoreException()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.GET, docRef.path, auth)
                    }
                    throw error
                }
        )
    }

    fun observeSocialAccounts(userId: String): Flow<List<SocialAccount>> = flow {
        val colRef = userDoc(userId).collection("social_accounts")
        emitAll(
            colRef.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(SocialAccount::class.java, ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = error.unwrapFirestoreException()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path, auth)
                    }
                    throw error
                }
        )
    }

    fun observeConversations(userId: String): Flow<List<Conversation>> = flow {
        val colRef = userDoc(userId).collection("conversations")
        emitAll(
            colRef.orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(Conversation::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedWith(
                            compareByDescending<Conversation> { it.isPinned }
                                .thenByDescending { it.updatedAt ?: Timestamp(0, 0) }
                        )
                }
                .catch { error ->
                    val firestoreError = error.unwrapFirestoreException()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path, auth)
                    }
                    throw error
                }
        )
    }

    fun observeMessages(userId: String, conversationId: String): Flow<List<ChatMessage>> = flow {
        val colRef = userDoc(userId)
            .collection("conversations")
            .document(conversationId)
            .collection("messages")
        emitAll(
            colRef.orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(ChatMessage::class.java, ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = error.unwrapFirestoreException()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path, auth)
                    }
                    throw error
                }
        )
    }

    fun observeCatalog(userId: String): Flow<List<CatalogItem>> = flow {
        val colRef = userDoc(userId).collection("catalog")
        emitAll(
            colRef.orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(CatalogItem::class.java, ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = error.unwrapFirestoreException()
                    if (firestoreError == null && error is CancellationException) throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(firestoreError ?: error, OperationType.LIST, colRef.path, auth)
                    }
                    throw error
                }
        )
    }

    suspend fun ensureUserProfile(
        displayName: String,
        email: String?,
        photoUrl: String?
    ) {
        val uid = requireUserId()
        val docRef = userDoc(uid)
        try {
            val snap = docRef.get().await()
            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to displayName.ifBlank { "مستخدم وصلة" }.take(80),
                    "email" to email?.take(160),
                    "photoUrl" to photoUrl?.take(500),
                    "stealthDefaultEnabled" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                docRef.set(payload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            throw e
        }
    }

    suspend fun updateGlobalStealthDefault(enabled: Boolean) {
        val uid = requireUserId()
        val docRef = userDoc(uid)
        try {
            docRef.update(
                mapOf(
                    "stealthDefaultEnabled" to enabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun upsertSocialAccount(
        platform: SocialPlatform,
        handle: String,
        displayName: String,
        isConnected: Boolean,
        autoShieldNewChats: Boolean,
        hideReadReceipts: Boolean
    ) {
        val uid = requireUserId()
        val docRef = userDoc(uid).collection("social_accounts").document(platform.id)
        try {
            val existing = docRef.get().await()
            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "handle" to handle.trim().take(80),
                        "displayName" to displayName.trim().ifBlank { platform.arabicLabel }.take(80),
                        "isConnected" to isConnected,
                        "autoShieldNewChats" to autoShieldNewChats,
                        "hideReadReceipts" to hideReadReceipts,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "platform" to platform.id,
                        "handle" to handle.trim().take(80),
                        "displayName" to displayName.trim().ifBlank { platform.arabicLabel }.take(80),
                        "isConnected" to isConnected,
                        "autoShieldNewChats" to autoShieldNewChats,
                        "hideReadReceipts" to hideReadReceipts,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            throw e
        }
    }

    suspend fun createConversation(
        contactName: String,
        contactHandle: String,
        platform: SocialPlatform,
        isInternetCutOff: Boolean,
        silentReadOnly: Boolean = true,
        appearOnlineToContact: Boolean = true,
        initialMessage: String? = null
    ): String {
        val uid = requireUserId()
        val convRef = userDoc(uid).collection("conversations").document()
        val cleanMsg = initialMessage?.trim().orEmpty()
        val convPayload = mapOf(
            "userId" to uid,
            "contactName" to contactName.trim().take(80),
            "contactHandle" to contactHandle.trim().take(80),
            "platform" to platform.id,
            "lastMessage" to cleanMsg.ifBlank { "محادثة جديدة على ${platform.arabicLabel}" }.take(500),
            "unreadCount" to 0L,
            "shieldedCount" to if (isInternetCutOff && cleanMsg.isNotEmpty()) 1L else 0L,
            "isInternetCutOff" to isInternetCutOff,
            "silentReadOnly" to silentReadOnly,
            "appearOnlineToContact" to appearOnlineToContact,
            "isPinned" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        try {
            if (cleanMsg.isNotEmpty()) {
                val batch = db.batch()
                batch.set(convRef, convPayload)
                val msgRef = convRef.collection("messages").document()
                val msgPayload = mapOf(
                    "userId" to uid,
                    "conversationId" to convRef.id,
                    "text" to cleanMsg.take(2000),
                    "isOutgoing" to false,
                    "deliveryStatus" to if (isInternetCutOff) DeliveryStatus.SHIELDED_HELD.id else DeliveryStatus.DELIVERED.id,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                batch.set(msgRef, msgPayload)
                batch.commit().await()
            } else {
                convRef.set(convPayload).await()
            }
            return convRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, convRef.path, auth)
            throw e
        }
    }

    suspend fun toggleInternetCutoff(
        conversationId: String,
        cutOff: Boolean,
        silentReadOnly: Boolean,
        appearOnlineToContact: Boolean
    ) {
        val uid = requireUserId()
        val convRef = userDoc(uid).collection("conversations").document(conversationId)
        try {
            if (!cutOff) {
                // Restoring internet to this conversation: flush queued outgoing & release held messages
                val messagesSnap = convRef.collection("messages").get().await()
                val batch = db.batch()
                batch.update(
                    convRef,
                    mapOf(
                        "isInternetCutOff" to false,
                        "silentReadOnly" to silentReadOnly,
                        "appearOnlineToContact" to appearOnlineToContact,
                        "shieldedCount" to 0L,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                messagesSnap.documents.forEach { doc ->
                    val status = doc.getString("deliveryStatus")
                    if (status == DeliveryStatus.QUEUED_OFFLINE.id) {
                        batch.update(doc.reference, "deliveryStatus", DeliveryStatus.SENT.id)
                    } else if (status == DeliveryStatus.SHIELDED_HELD.id) {
                        batch.update(doc.reference, "deliveryStatus", DeliveryStatus.DELIVERED.id)
                    }
                }
                batch.commit().await()
            } else {
                convRef.update(
                    mapOf(
                        "isInternetCutOff" to true,
                        "silentReadOnly" to silentReadOnly,
                        "appearOnlineToContact" to appearOnlineToContact,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, convRef.path, auth)
            throw e
        }
    }

    suspend fun togglePinConversation(conversationId: String, isPinned: Boolean) {
        val uid = requireUserId()
        val convRef = userDoc(uid).collection("conversations").document(conversationId)
        try {
            convRef.update(
                mapOf(
                    "isPinned" to isPinned,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, convRef.path, auth)
            throw e
        }
    }

    suspend fun markConversationRead(conversationId: String, isInternetCutOff: Boolean, silentReadOnly: Boolean) {
        val uid = requireUserId()
        val convRef = userDoc(uid).collection("conversations").document(conversationId)
        try {
            convRef.update(
                mapOf(
                    "unreadCount" to 0L,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            // Only emit READ receipts if internet is active AND silentReadOnly is off
            if (!isInternetCutOff && !silentReadOnly) {
                val msgs = convRef.collection("messages")
                    .whereEqualTo("isOutgoing", false)
                    .get()
                    .await()
                val batch = db.batch()
                var hasUpdates = false
                msgs.documents.forEach { doc ->
                    val status = doc.getString("deliveryStatus")
                    if (status == DeliveryStatus.DELIVERED.id) {
                        batch.update(doc.reference, "deliveryStatus", DeliveryStatus.READ.id)
                        hasUpdates = true
                    }
                }
                if (hasUpdates) {
                    batch.commit().await()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, convRef.path, auth)
            throw e
        }
    }

    suspend fun sendMessage(
        conversationId: String,
        text: String,
        isInternetCutOff: Boolean,
        product: CatalogItem? = null
    ) {
        val uid = requireUserId()
        val convRef = userDoc(uid).collection("conversations").document(conversationId)
        val msgRef = convRef.collection("messages").document()
        val cleanText = text.trim()
        if (cleanText.isEmpty() && product == null) return

        val preview = when {
            product != null && cleanText.isNotEmpty() -> "${product.title}: $cleanText"
            product != null -> "كتالوج: ${product.title}"
            else -> cleanText
        }.take(500)

        val deliveryStatus = if (isInternetCutOff) {
            DeliveryStatus.QUEUED_OFFLINE.id
        } else {
            DeliveryStatus.SENT.id
        }

        val formattedPrice = product?.let {
            String.format(Locale.US, "%.2f %s", it.price, it.currency).replace(".00 ", " ")
        }

        val msgPayload = mapOf(
            "userId" to uid,
            "conversationId" to conversationId,
            "text" to cleanText.ifBlank { product?.description ?: "" }.take(2000),
            "isOutgoing" to true,
            "deliveryStatus" to deliveryStatus,
            "productId" to product?.id?.takeIf { it.isNotBlank() },
            "productTitle" to product?.title?.take(100),
            "productPrice" to formattedPrice?.take(40),
            "productImageKey" to product?.presetImageKey?.take(500),
            "createdAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }

        try {
            val batch = db.batch()
            batch.set(msgRef, msgPayload)
            batch.update(
                convRef,
                mapOf(
                    "lastMessage" to preview,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path, auth)
            throw e
        }
    }

    suspend fun receiveIncomingMessage(
        conversationId: String,
        text: String,
        isInternetCutOff: Boolean
    ) {
        val uid = requireUserId()
        val convRef = userDoc(uid).collection("conversations").document(conversationId)
        val msgRef = convRef.collection("messages").document()
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        val status = if (isInternetCutOff) DeliveryStatus.SHIELDED_HELD.id else DeliveryStatus.DELIVERED.id
        val msgPayload = mapOf(
            "userId" to uid,
            "conversationId" to conversationId,
            "text" to cleanText.take(2000),
            "isOutgoing" to false,
            "deliveryStatus" to status,
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            val batch = db.batch()
            batch.set(msgRef, msgPayload)
            val convUpdate = if (isInternetCutOff) {
                mapOf(
                    "lastMessage" to cleanText.take(500),
                    "shieldedCount" to FieldValue.increment(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            } else {
                mapOf(
                    "lastMessage" to cleanText.take(500),
                    "unreadCount" to FieldValue.increment(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            }
            batch.update(convRef, convUpdate)
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path, auth)
            throw e
        }
    }

    suspend fun deleteConversation(conversationId: String) {
        val uid = requireUserId()
        val convRef = userDoc(uid).collection("conversations").document(conversationId)
        try {
            val msgs = convRef.collection("messages").get().await()
            val batch = db.batch()
            msgs.documents.forEach { batch.delete(it.reference) }
            batch.delete(convRef)
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, convRef.path, auth)
            throw e
        }
    }

    suspend fun upsertCatalogItem(
        itemId: String? = null,
        title: String,
        description: String,
        price: Double,
        currency: String,
        category: String,
        presetImageKey: String,
        inStock: Boolean
    ): String {
        val uid = requireUserId()
        val colRef = userDoc(uid).collection("catalog")
        val docRef = if (itemId.isNullOrBlank()) colRef.document() else colRef.document(itemId)
        try {
            if (itemId.isNullOrBlank()) {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "title" to title.trim().take(100),
                        "description" to description.trim().take(600),
                        "price" to price.coerceIn(0.0, 10_000_000.0),
                        "currency" to currency.trim().ifBlank { "ر.س" }.take(10),
                        "category" to category.trim().ifBlank { "عام" }.take(50),
                        "presetImageKey" to presetImageKey.take(500),
                        "inStock" to inStock,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.update(
                    mapOf(
                        "title" to title.trim().take(100),
                        "description" to description.trim().take(600),
                        "price" to price.coerceIn(0.0, 10_000_000.0),
                        "currency" to currency.trim().ifBlank { "ر.س" }.take(10),
                        "category" to category.trim().ifBlank { "عام" }.take(50),
                        "presetImageKey" to presetImageKey.take(500),
                        "inStock" to inStock,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            return docRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path, auth)
            throw e
        }
    }

    suspend fun toggleCatalogStock(itemId: String, inStock: Boolean) {
        val uid = requireUserId()
        val docRef = userDoc(uid).collection("catalog").document(itemId)
        try {
            docRef.update(
                mapOf(
                    "inStock" to inStock,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path, auth)
            throw e
        }
    }

    suspend fun deleteCatalogItem(itemId: String) {
        val uid = requireUserId()
        val docRef = userDoc(uid).collection("catalog").document(itemId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path, auth)
            throw e
        }
    }

    suspend fun seedStarterWorkspace() {
        val uid = requireUserId()
        val batch = db.batch()
        val accountsCol = userDoc(uid).collection("social_accounts")
        val starterAccounts = listOf(
            SocialAccount(
                platform = SocialPlatform.WHATSAPP.id,
                handle = "+966 55 019 2834",
                displayName = "واتساب للأعمال",
                isConnected = true,
                autoShieldNewChats = false,
                hideReadReceipts = true
            ),
            SocialAccount(
                platform = SocialPlatform.INSTAGRAM.id,
                handle = "@wasla.store",
                displayName = "حساب إنستقرام الرسمي",
                isConnected = true,
                autoShieldNewChats = true,
                hideReadReceipts = true
            ),
            SocialAccount(
                platform = SocialPlatform.FACEBOOK.id,
                handle = "fb.com/wasla.page",
                displayName = "صفحة فيسبوك ماسنجر",
                isConnected = true,
                autoShieldNewChats = false,
                hideReadReceipts = true
            ),
            SocialAccount(
                platform = SocialPlatform.SNAPCHAT.id,
                handle = "wasla.snap",
                displayName = "سناب شات",
                isConnected = false,
                autoShieldNewChats = true,
                hideReadReceipts = true
            )
        )
        starterAccounts.forEach { acc ->
            batch.set(
                accountsCol.document(acc.platform),
                mapOf(
                    "userId" to uid,
                    "platform" to acc.platform,
                    "handle" to acc.handle,
                    "displayName" to acc.displayName,
                    "isConnected" to acc.isConnected,
                    "autoShieldNewChats" to acc.autoShieldNewChats,
                    "hideReadReceipts" to acc.hideReadReceipts,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        val catalogCol = userDoc(uid).collection("catalog")
        val watchRef = catalogCol.document()
        batch.set(
            watchRef,
            mapOf(
                "userId" to uid,
                "title" to "ساعة تيتانيوم الذكية",
                "description" to "إطار تيتانيوم مقاوم للماء مع شاشة OLED عالية السطوع وبطارية تدوم 10 أيام.",
                "price" to 649.0,
                "currency" to "ر.س",
                "category" to "إلكترونيات",
                "presetImageKey" to "watch",
                "inStock" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        val earbudsRef = catalogCol.document()
        batch.set(
            earbudsRef,
            mapOf(
                "userId" to uid,
                "title" to "سماعات عزل الضوضاء Pro",
                "description" to "صوت محيطي فائق مع عزل ضوضاء ذكي وحافظة شحن مغناطيسية أنيقة.",
                "price" to 389.0,
                "currency" to "ر.س",
                "category" to "صوتيات",
                "presetImageKey" to "earbuds",
                "inStock" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        val convCol = userDoc(uid).collection("conversations")
        val convWa = convCol.document()
        batch.set(
            convWa,
            mapOf(
                "userId" to uid,
                "contactName" to "نورة العتيبي",
                "contactHandle" to "+966 50 882 1190",
                "platform" to SocialPlatform.WHATSAPP.id,
                "lastMessage" to "أهلاً، هل متوفرة الساعة الذكية في الكتالوج؟",
                "unreadCount" to 1L,
                "shieldedCount" to 0L,
                "isInternetCutOff" to false,
                "silentReadOnly" to true,
                "appearOnlineToContact" to true,
                "isPinned" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            convWa.collection("messages").document(),
            mapOf(
                "userId" to uid,
                "conversationId" to convWa.id,
                "text" to "أهلاً، هل متوفرة الساعة الذكية في الكتالوج؟",
                "isOutgoing" to false,
                "deliveryStatus" to DeliveryStatus.DELIVERED.id,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        val convIg = convCol.document()
        batch.set(
            convIg,
            mapOf(
                "userId" to uid,
                "contactName" to "فهد الدوسري",
                "contactHandle" to "@fahad.d99",
                "platform" to SocialPlatform.INSTAGRAM.id,
                "lastMessage" to "مساء الخير، ليش ما ترد على الرسائل؟",
                "unreadCount" to 0L,
                "shieldedCount" to 2L,
                "isInternetCutOff" to true,
                "silentReadOnly" to true,
                "appearOnlineToContact" to true,
                "isPinned" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            convIg.collection("messages").document(),
            mapOf(
                "userId" to uid,
                "conversationId" to convIg.id,
                "text" to "مساء الخير، ليش ما ترد على الرسائل؟",
                "isOutgoing" to false,
                "deliveryStatus" to DeliveryStatus.SHIELDED_HELD.id,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        val convFb = convCol.document()
        batch.set(
            convFb,
            mapOf(
                "userId" to uid,
                "contactName" to "متجر الأناقة",
                "contactHandle" to "fb.com/elegance.sa",
                "platform" to SocialPlatform.FACEBOOK.id,
                "lastMessage" to "تم تأكيد شحنة الطلب رقم #4410",
                "unreadCount" to 0L,
                "shieldedCount" to 0L,
                "isInternetCutOff" to false,
                "silentReadOnly" to true,
                "appearOnlineToContact" to true,
                "isPinned" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            convFb.collection("messages").document(),
            mapOf(
                "userId" to uid,
                "conversationId" to convFb.id,
                "text" to "تم تأكيد شحنة الطلب رقم #4410",
                "isOutgoing" to false,
                "deliveryStatus" to DeliveryStatus.READ.id,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        val convSnap = convCol.document()
        batch.set(
            convSnap,
            mapOf(
                "userId" to uid,
                "contactName" to "ريم خالد",
                "contactHandle" to "reem.kh24",
                "platform" to SocialPlatform.SNAPCHAT.id,
                "lastMessage" to "أرسلت لك سناب جديد — محفوظ بصمت",
                "unreadCount" to 0L,
                "shieldedCount" to 1L,
                "isInternetCutOff" to true,
                "silentReadOnly" to true,
                "appearOnlineToContact" to true,
                "isPinned" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )
        batch.set(
            convSnap.collection("messages").document(),
            mapOf(
                "userId" to uid,
                "conversationId" to convSnap.id,
                "text" to "أرسلت لك سناب جديد — محفوظ بصمت",
                "isOutgoing" to false,
                "deliveryStatus" to DeliveryStatus.SHIELDED_HELD.id,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )

        try {
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userDoc(uid).path, auth)
            throw e
        }
    }
}
