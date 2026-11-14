package com.example.data

import com.example.base.FirestoreEmulatorTestBase
import com.example.model.DeliveryStatus
import com.example.model.SocialPlatform
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WaslaRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createAndToggleInternetCutoff_persistsAndRestoresMessages() = runBlocking {
        val uid = signInTestUser("alice_${UUID.randomUUID()}@test.com")
        val repository = WaslaRepository(firestore, auth)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.ensureUserProfile("أليس", "alice@test.com", null)
        }

        val convId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createConversation(
                contactName = "عمر العتيبي",
                contactHandle = "@omar.ig",
                platform = SocialPlatform.INSTAGRAM,
                isInternetCutOff = true,
                initialMessage = "مرحباً هل أنت متصل؟"
            )
        }
        assertTrue(convId.isNotBlank())

        val convs = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeConversations(uid).first { list -> list.any { it.id == convId } }
        }
        val created = convs.first { it.id == convId }
        assertTrue(created.isInternetCutOff)
        assertEquals(SocialPlatform.INSTAGRAM, created.platformEnum)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.sendMessage(convId, "رسالة مؤجلة", isInternetCutOff = true)
        }

        val heldMsgs = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMessages(uid, convId).first { list -> list.size >= 2 }
        }
        assertTrue(heldMsgs.any { it.deliveryStatus == DeliveryStatus.QUEUED_OFFLINE.id })

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.toggleInternetCutoff(
                conversationId = convId,
                cutOff = false,
                silentReadOnly = true,
                appearOnlineToContact = true
            )
        }

        val restoredMsgs = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMessages(uid, convId).first { list ->
                list.none { it.deliveryStatus == DeliveryStatus.QUEUED_OFFLINE.id }
            }
        }
        assertFalse(restoredMsgs.any { it.deliveryStatus == DeliveryStatus.QUEUED_OFFLINE.id })
    }

    @Test
    fun catalogCrudAndShareToChat_succeedsForOwner() = runBlocking {
        val uid = signInTestUser("catalog_${UUID.randomUUID()}@test.com")
        val repository = WaslaRepository(firestore, auth)

        val itemId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.upsertCatalogItem(
                title = "ساعة تيتانيوم",
                description = "وصف المنتج",
                price = 550.0,
                currency = "ر.س",
                category = "إلكترونيات",
                presetImageKey = "watch",
                inStock = true
            )
        }
        val items = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeCatalog(uid).first { list -> list.any { it.id == itemId } }
        }
        val item = items.first { it.id == itemId }
        assertEquals("ساعة تيتانيوم", item.title)

        val convId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createConversation(
                contactName = "سعد",
                contactHandle = "+966550001122",
                platform = SocialPlatform.WHATSAPP,
                isInternetCutOff = false
            )
        }

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.sendMessage(convId, "تفاصيل المنتج", isInternetCutOff = false, product = item)
        }

        val msgs = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMessages(uid, convId).first { list -> list.any { it.hasProductCard } }
        }
        assertEquals("ساعة تيتانيوم", msgs.first().productTitle)
    }

    @Test
    fun observeConversations_unauthenticated_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = WaslaRepository(firestore, auth)

        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeConversations("someone_uid").first()
            }
        } catch (t: Throwable) {
            caught = t
        }
        assertNotNull(caught)
        val firestoreEx = caught?.unwrapFirestoreException()
        assertNotNull("Expected nested FirebaseFirestoreException, got $caught", firestoreEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    @Test
    fun crossUserRead_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser("alice_iso_${UUID.randomUUID()}@test.com")
        val aliceRepo = WaslaRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createConversation(
                contactName = "خاص",
                contactHandle = "@priv",
                platform = SocialPlatform.WHATSAPP,
                isInternetCutOff = true
            )
        }

        signInTestUser("bob_iso_${UUID.randomUUID()}@test.com")
        val bobRepo = WaslaRepository(firestore, auth)
        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeConversations(aliceUid).first()
            }
        } catch (t: Throwable) {
            caught = t
        }
        assertNotNull(caught)
        val firestoreEx = caught?.unwrapFirestoreException()
        assertNotNull("Expected nested FirebaseFirestoreException, got $caught", firestoreEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3500L
    }
}
