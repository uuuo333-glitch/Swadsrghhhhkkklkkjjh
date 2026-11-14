const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const pastDate = () => new Date(Date.now() - 1000);

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function validConversation(uid, platform = "whatsapp") {
  return {
    userId: uid,
    contactName: "سارة أحمد",
    contactHandle: "@sara_chat",
    platform,
    lastMessage: "مرحبا",
    unreadCount: 0,
    shieldedCount: 1,
    isInternetCutOff: true,
    silentReadOnly: true,
    appearOnlineToContact: true,
    isPinned: false,
    createdAt: pastDate(),
    updatedAt: pastDate(),
  };
}

function validCatalogItem(uid) {
  return {
    userId: uid,
    title: "ساعة ذكية برو",
    description: "ساعة ذكية بإطار تيتانيوم",
    price: 499.0,
    currency: "ر.س",
    category: "إلكترونيات",
    presetImageKey: "watch",
    inStock: true,
    createdAt: pastDate(),
    updatedAt: pastDate(),
  };
}

test("Unauthenticated user cannot read or write conversations", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("conversations").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv1").set(validConversation(ALICE_UID))
  );
});

test("Cross-user isolation: Bob cannot read or modify Alice's conversations or catalog", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv1").set(validConversation(ALICE_UID))
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("catalog").doc("prod1").set(validCatalogItem(ALICE_UID))
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("conversations").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("catalog").doc("prod1").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv1").update({ isInternetCutOff: false })
  );
});

test("Owner can create and toggle internet cutoff on WhatsApp & Instagram conversations", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const convRef = aliceDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_ig_1");
  await assertSucceeds(convRef.set(validConversation(ALICE_UID, "instagram")));
  await assertSucceeds(
    convRef.update({
      isInternetCutOff: false,
      shieldedCount: 0,
      updatedAt: pastDate(),
    })
  );
});

test("Shadow update with ghost field or invalid platform fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("conversations")
      .doc("conv_bad")
      .set({ ...validConversation(ALICE_UID), isAdmin: true })
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("conversations")
      .doc("conv_bad_plat")
      .set(validConversation(ALICE_UID, "tiktok_unknown"))
  );
});

test("Cannot mutate immutable platform or userId on conversation update", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const convRef = aliceDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_imm");
  await assertSucceeds(convRef.set(validConversation(ALICE_UID, "whatsapp")));
  await assertFails(
    convRef.update({
      platform: "instagram",
      updatedAt: pastDate(),
    })
  );
});

test("Message creation enforces parent conversation existence and delivery status validation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const orphanMsgRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("conversations")
    .doc("non_existent_conv")
    .collection("messages")
    .doc("msg1");

  await assertFails(
    orphanMsgRef.set({
      userId: ALICE_UID,
      conversationId: "non_existent_conv",
      text: "Hello",
      isOutgoing: true,
      deliveryStatus: "sent",
      createdAt: pastDate(),
    })
  );

  const convRef = aliceDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_parent");
  await assertSucceeds(convRef.set(validConversation(ALICE_UID)));

  const validMsgRef = convRef.collection("messages").doc("msg1");
  await assertSucceeds(
    validMsgRef.set({
      userId: ALICE_UID,
      conversationId: "conv_parent",
      text: "تم إرسال منتج من الكتالوج",
      isOutgoing: true,
      deliveryStatus: "queued_offline",
      productId: "prod1",
      productTitle: "ساعة ذكية برو",
      productPrice: "499 ر.س",
      productImageKey: "watch",
      createdAt: pastDate(),
    })
  );

  await assertSucceeds(validMsgRef.update({ deliveryStatus: "sent" }));
  await assertFails(validMsgRef.update({ text: "tampered" }));
});

test("Catalog CRUD enforces schema validation and price bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const itemRef = aliceDb.collection("users").doc(ALICE_UID).collection("catalog").doc("item_1");
  await assertSucceeds(itemRef.set(validCatalogItem(ALICE_UID)));
  await assertSucceeds(itemRef.update({ inStock: false, price: 399.0, updatedAt: pastDate() }));

  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("catalog")
      .doc("item_neg")
      .set({ ...validCatalogItem(ALICE_UID), price: -10 })
  );
});
