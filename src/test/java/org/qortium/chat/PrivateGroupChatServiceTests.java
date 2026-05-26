package org.qortium.chat;

import org.junit.Before;
import org.junit.Test;
import org.qortium.chat.crypto.PrivateGroupChatCrypto;
import org.qortium.chat.crypto.PrivateGroupChatEnvelope;
import org.qortium.chat.crypto.PrivateGroupChatKeyAnnouncement;
import org.qortium.chat.crypto.PrivateGroupChatKeyCache;
import org.qortium.chat.crypto.PrivateGroupChatKeyRequest;
import org.qortium.chat.crypto.PrivateGroupChatMembership;
import org.qortium.chat.crypto.PrivateGroupChatRotationRequest;
import org.qortium.data.group.GroupAdminData;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;
import org.qortium.transaction.ChatTransaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transform.Transformer;
import org.qortium.utils.NTP;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrivateGroupChatServiceTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		PrivateGroupChatKeyCache.getInstance().clear();
	}

	@Test
	public void testFirstSendStoresKeyAnnouncementAndMessage() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-first-send");

			PrivateGroupChatService.SendResult result = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("secret"), true, null);

			assertNotNull(result.getKeyAnnouncementSignature());
			assertNotNull(result.getMessageSignature());
			assertNotNull(result.getEpochId());
			assertNotNull(result.getKeyId());

			ChatTransactionData keyAnnouncementData = repository.getChatStoreRepository().fromSignature(
					result.getKeyAnnouncementSignature());
			ChatTransactionData messageData = repository.getChatStoreRepository().fromSignature(result.getMessageSignature());
			assertNotNull(keyAnnouncementData);
			assertNotNull(messageData);
			assertEquals(fixture.groupId, keyAnnouncementData.getTxGroupId());
			assertEquals(fixture.groupId, messageData.getTxGroupId());
			assertTrue(keyAnnouncementData.getIsEncrypted());
			assertTrue(messageData.getIsEncrypted());

			assertEquals(PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT,
					PrivateGroupChatEnvelope.fromBytes(keyAnnouncementData.getData()).getType());
			assertEquals(PrivateGroupChatEnvelope.Type.MESSAGE,
					PrivateGroupChatEnvelope.fromBytes(messageData.getData()).getType());
		}
	}

	@Test
	public void testSecondSendReusesCachedKey() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-reuse");

			PrivateGroupChatService.SendResult firstResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("first"), true, null);
			PrivateGroupChatService.SendResult secondResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("second"), true, firstResult.getMessageSignature());

			assertNotNull(firstResult.getKeyAnnouncementSignature());
			assertNull(secondResult.getKeyAnnouncementSignature());
			assertArrayEquals(firstResult.getEpochId(), secondResult.getEpochId());
			assertArrayEquals(firstResult.getKeyId(), secondResult.getKeyId());

			ChatTransactionData secondMessageData = repository.getChatStoreRepository().fromSignature(
					secondResult.getMessageSignature());
			assertArrayEquals(firstResult.getMessageSignature(), secondMessageData.getChatReference());
		}
	}

	@Test
	public void testCurrentMembersCanDecryptCachedMessage() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-decrypt");
			byte[] plaintext = bytes("member secret");

			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, plaintext, true, null);
			PrivateGroupChatService.DecryptResult decryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), sendResult.getMessageSignature());

			assertArrayEquals(plaintext, decryptResult.getData());
			assertTrue(decryptResult.isText());
			assertEquals(fixture.groupId, decryptResult.getGroupId());
			assertArrayEquals(sendResult.getEpochId(), decryptResult.getEpochId());
			assertArrayEquals(sendResult.getKeyId(), decryptResult.getKeyId());
		}
	}

	@Test
	public void testNonMemberCannotSendOrDecrypt() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-nonmember");

			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatService.getInstance().send(repository, fixture.chloe.getPrivateKey(),
							fixture.groupId, bytes("blocked"), true, null));

			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("secret"), true, null);

			assertThrows(PrivateGroupChatService.PrivateGroupChatException.class,
					() -> PrivateGroupChatService.getInstance().decrypt(repository, fixture.chloe.getPrivateKey(),
							sendResult.getMessageSignature()));
		}
	}

	@Test
	public void testOpenGroupIsRejected() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			int groupId = GroupUtils.createGroup(repository, alice, "private-service-open", true,
					ApprovalThreshold.ONE, 10, 40);

			assertThrows(IllegalArgumentException.class,
					() -> PrivateGroupChatService.getInstance().send(repository, alice.getPrivateKey(), groupId,
							bytes("not private"), true, null));
		}
	}

	@Test
	public void testRequestKeyStoresKeyRequestEnvelope() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-key-request");
			byte[] keyId = bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 5);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);

			PrivateGroupChatService.KeyRequestResult result = PrivateGroupChatService.getInstance().requestKey(repository,
					fixture.bob.getPrivateKey(), fixture.groupId, keyId);

			assertNotNull(result.getRequestSignature());
			assertArrayEquals(epoch.getEpochId(), result.getEpochId());
			assertArrayEquals(keyId, result.getKeyId());

			ChatTransactionData keyRequestData = repository.getChatStoreRepository().fromSignature(
					result.getRequestSignature());
			assertNotNull(keyRequestData);
			assertEquals(fixture.groupId, keyRequestData.getTxGroupId());
			assertEquals(fixture.bob.getAddress(), keyRequestData.getSender());
			assertFalse(keyRequestData.getIsText());
			assertTrue(keyRequestData.getIsEncrypted());

			PrivateGroupChatEnvelope keyRequest = PrivateGroupChatEnvelope.fromBytes(keyRequestData.getData());
			assertEquals(PrivateGroupChatEnvelope.Type.KEY_REQUEST, keyRequest.getType());
			assertArrayEquals(fixture.bob.getPublicKey(), keyRequest.getRequesterPublicKey());
			assertArrayEquals(keyId, keyRequest.getKeyId());
			assertEquals(ValidationResult.TRANSACTION_ALREADY_EXISTS,
					ChatService.getInstance().validateForStore(repository, keyRequestData));
		}
	}

	@Test
	public void testRequestKeyWithoutSpecificKeyIdAndNonMemberRejection() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-key-request-any");

			PrivateGroupChatService.KeyRequestResult result = PrivateGroupChatService.getInstance().requestKey(repository,
					fixture.bob.getPrivateKey(), fixture.groupId, null);

			assertNotNull(result.getRequestSignature());
			assertNotNull(result.getEpochId());
			assertNull(result.getKeyId());

			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatService.getInstance().requestKey(repository, fixture.chloe.getPrivateKey(),
							fixture.groupId, null));
		}
	}

	@Test
	public void testRelayKeyAnnouncementStoresExistingAnnouncementEnvelope() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-relay-stored");
			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("secret"), true, null);
			ChatTransactionData originalAnnouncementData = repository.getChatStoreRepository().fromSignature(
					sendResult.getKeyAnnouncementSignature());
			PrivateGroupChatEnvelope originalAnnouncement = PrivateGroupChatEnvelope.fromBytes(
					originalAnnouncementData.getData());

			PrivateGroupChatService.KeyAnnouncementRelayResult relayResult = PrivateGroupChatService.getInstance()
					.relayKeyAnnouncement(repository, fixture.bob.getPrivateKey(), fixture.groupId,
							sendResult.getEpochId(), sendResult.getKeyId());

			assertNotNull(relayResult.getAnnouncementSignature());
			assertArrayEquals(sendResult.getEpochId(), relayResult.getEpochId());
			assertArrayEquals(sendResult.getKeyId(), relayResult.getKeyId());

			ChatTransactionData relayData = repository.getChatStoreRepository().fromSignature(
					relayResult.getAnnouncementSignature());
			assertNotNull(relayData);
			assertEquals(fixture.bob.getAddress(), relayData.getSender());
			assertFalse(relayData.getIsText());
			assertTrue(relayData.getIsEncrypted());
			assertArrayEquals(originalAnnouncement.toBytes(), relayData.getData());
		}
	}

	@Test
	public void testRelayKeyAnnouncementCanUseCachedAnnouncement() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-relay-cache");
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 20);
			PrivateGroupChatEnvelope keyAnnouncement = PrivateGroupChatKeyAnnouncement.create(epoch,
					groupKey, fixture.alice.getPrivateKey());
			PrivateGroupChatKeyCache.getInstance().putLocal(epoch, keyAnnouncement, groupKey);

			PrivateGroupChatService.KeyAnnouncementRelayResult relayResult = PrivateGroupChatService.getInstance()
					.relayKeyAnnouncement(repository, fixture.bob.getPrivateKey(), fixture.groupId,
							epoch.getEpochId(), null);

			assertNotNull(relayResult.getAnnouncementSignature());
			assertArrayEquals(epoch.getEpochId(), relayResult.getEpochId());
			assertArrayEquals(keyAnnouncement.getKeyId(), relayResult.getKeyId());

			ChatTransactionData relayData = repository.getChatStoreRepository().fromSignature(
					relayResult.getAnnouncementSignature());
			assertArrayEquals(keyAnnouncement.toBytes(), relayData.getData());
		}
	}

	@Test
	public void testRelayKeyAnnouncementRejectsMissingOrUnauthorizedRelay() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-relay-missing");
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);

			assertThrows(PrivateGroupChatService.PrivateGroupChatException.class,
					() -> PrivateGroupChatService.getInstance().relayKeyAnnouncement(repository,
							fixture.bob.getPrivateKey(), fixture.groupId, epoch.getEpochId(),
							bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 30)));

			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatService.getInstance().relayKeyAnnouncement(repository,
							fixture.chloe.getPrivateKey(), fixture.groupId, epoch.getEpochId(), null));
		}
	}

	@Test
	public void testResolveKeyRequestsRelaysSpecificKeyRequest() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-resolve-specific");
			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("secret"), true, null);
			PrivateGroupChatService.KeyRequestResult keyRequestResult = PrivateGroupChatService.getInstance()
					.requestKey(repository, fixture.bob.getPrivateKey(), fixture.groupId, sendResult.getKeyId());

			List<PrivateGroupChatService.KeyRequestRecoveryResult> results = PrivateGroupChatService.getInstance()
					.resolveKeyRequests(repository, fixture.alice.getPrivateKey(), fixture.groupId, null);

			assertEquals(1, results.size());
			PrivateGroupChatService.KeyRequestRecoveryResult result = results.get(0);
			assertEquals(PrivateGroupChatService.KeyRequestRecoveryStatus.RELAYED, result.getStatus());
			assertArrayEquals(keyRequestResult.getRequestSignature(), result.getRequestSignature());
			assertArrayEquals(fixture.bob.getPublicKey(), result.getRequesterPublicKey());
			assertArrayEquals(sendResult.getEpochId(), result.getEpochId());
			assertArrayEquals(sendResult.getKeyId(), result.getRequestedKeyId());
			assertArrayEquals(sendResult.getKeyId(), result.getRelayedKeyId());
			assertNotNull(result.getAnnouncementSignature());

			ChatTransactionData originalAnnouncementData = repository.getChatStoreRepository().fromSignature(
					sendResult.getKeyAnnouncementSignature());
			ChatTransactionData relayedAnnouncementData = repository.getChatStoreRepository().fromSignature(
					result.getAnnouncementSignature());
			assertNotNull(relayedAnnouncementData);
			assertEquals(fixture.alice.getAddress(), relayedAnnouncementData.getSender());
			assertArrayEquals(originalAnnouncementData.getData(), relayedAnnouncementData.getData());
		}
	}

	@Test
	public void testResolveKeyRequestsRelaysAnyUsableKeyRequest() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-resolve-any");
			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("secret"), true, null);
			PrivateGroupChatService.getInstance().requestKey(repository, fixture.bob.getPrivateKey(),
					fixture.groupId, null);

			List<PrivateGroupChatService.KeyRequestRecoveryResult> results = PrivateGroupChatService.getInstance()
					.resolveKeyRequests(repository, fixture.alice.getPrivateKey(), fixture.groupId, null);

			assertEquals(1, results.size());
			assertEquals(PrivateGroupChatService.KeyRequestRecoveryStatus.RELAYED, results.get(0).getStatus());
			assertNull(results.get(0).getRequestedKeyId());
			assertArrayEquals(sendResult.getKeyId(), results.get(0).getRelayedKeyId());
			assertNotNull(results.get(0).getAnnouncementSignature());
		}
	}

	@Test
	public void testResolveKeyRequestsDeduplicatesRelayedKeys() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "priv-resolve-dupes");
			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("secret"), true, null);
			PrivateGroupChatService.getInstance().requestKey(repository, fixture.bob.getPrivateKey(),
					fixture.groupId, sendResult.getKeyId());
			PrivateGroupChatService.getInstance().requestKey(repository, fixture.alice.getPrivateKey(),
					fixture.groupId, sendResult.getKeyId());

			List<PrivateGroupChatService.KeyRequestRecoveryResult> results = PrivateGroupChatService.getInstance()
					.resolveKeyRequests(repository, fixture.bob.getPrivateKey(), fixture.groupId, null);

			assertEquals(2, results.size());
			assertEquals(1, countRecoveryStatus(results, PrivateGroupChatService.KeyRequestRecoveryStatus.RELAYED));
			assertEquals(1, countRecoveryStatus(results, PrivateGroupChatService.KeyRequestRecoveryStatus.DUPLICATE_KEY));
		}
	}

	@Test
	public void testResolveKeyRequestsReportsNoKeyAvailable() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-resolve-missing");
			byte[] missingKeyId = bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 80);
			PrivateGroupChatService.getInstance().requestKey(repository, fixture.bob.getPrivateKey(),
					fixture.groupId, missingKeyId);

			List<PrivateGroupChatService.KeyRequestRecoveryResult> results = PrivateGroupChatService.getInstance()
					.resolveKeyRequests(repository, fixture.alice.getPrivateKey(), fixture.groupId, null);

			assertEquals(1, results.size());
			assertEquals(PrivateGroupChatService.KeyRequestRecoveryStatus.NO_KEY_AVAILABLE, results.get(0).getStatus());
			assertArrayEquals(missingKeyId, results.get(0).getRequestedKeyId());
			assertNull(results.get(0).getRelayedKeyId());
			assertNull(results.get(0).getAnnouncementSignature());
		}
	}

	@Test
	public void testResolveKeyRequestsRecoversHistoricalKeyForCurrentMember() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "priv-resolve-history");
			byte[] plaintext = bytes("old epoch recovery");
			PrivateGroupChatMembership.MembershipEpoch oldEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(
					repository, fixture.groupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 100);
			byte[] keyId = PrivateGroupChatCrypto.computeKeyId(fixture.groupId, oldEpoch.getEpochId(), groupKey);
			byte[] nonce = PrivateGroupChatCrypto.generateNonce();
			byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, fixture.groupId,
					oldEpoch.getEpochId(), keyId, nonce, plaintext);
			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(fixture.groupId,
					oldEpoch.getEpochId(), keyId, nonce, ciphertext);
			ChatTransactionData messageData = signedChat(repository, fixture.alice, fixture.groupId,
					messageEnvelope.toBytes(), true, true, now());

			assertEquals(ValidationResult.OK, ChatService.getInstance().validateAndStore(repository, messageData));
			addMember(repository, fixture.groupId, fixture.chloe);
			PrivateGroupChatKeyCache.getInstance().clear();

			assertThrows(PrivateGroupChatService.PrivateGroupChatException.class,
					() -> PrivateGroupChatService.getInstance().decrypt(repository, fixture.bob.getPrivateKey(),
							messageData.getSignature()));

			PrivateGroupChatService.KeyRequestResult keyRequestResult = PrivateGroupChatService.getInstance()
					.requestKey(repository, fixture.bob.getPrivateKey(), fixture.groupId, oldEpoch.getEpochId(), keyId);
			assertArrayEquals(oldEpoch.getEpochId(), keyRequestResult.getEpochId());
			assertArrayEquals(keyId, keyRequestResult.getKeyId());

			PrivateGroupChatEnvelope keyAnnouncement = PrivateGroupChatKeyAnnouncement.create(oldEpoch, groupKey,
					fixture.alice.getPrivateKey());
			PrivateGroupChatKeyCache.getInstance().putLocal(oldEpoch, keyAnnouncement, groupKey);

			List<PrivateGroupChatService.KeyRequestRecoveryResult> results = PrivateGroupChatService.getInstance()
					.resolveKeyRequests(repository, fixture.alice.getPrivateKey(), fixture.groupId, null);

			assertEquals(1, results.size());
			PrivateGroupChatService.KeyRequestRecoveryResult result = results.get(0);
			assertEquals(PrivateGroupChatService.KeyRequestRecoveryStatus.RELAYED, result.getStatus());
			assertArrayEquals(keyRequestResult.getRequestSignature(), result.getRequestSignature());
			assertArrayEquals(oldEpoch.getEpochId(), result.getEpochId());
			assertArrayEquals(keyId, result.getRequestedKeyId());
			assertArrayEquals(keyId, result.getRelayedKeyId());
			assertNotNull(result.getAnnouncementSignature());

			PrivateGroupChatKeyCache.getInstance().clear();
			PrivateGroupChatService.DecryptResult decryptResult = PrivateGroupChatService.getInstance().decrypt(
					repository, fixture.bob.getPrivateKey(), messageData.getSignature());
			assertArrayEquals(plaintext, decryptResult.getData());

			assertThrows(PrivateGroupChatService.PrivateGroupChatException.class,
					() -> PrivateGroupChatService.getInstance().decrypt(repository, fixture.chloe.getPrivateKey(),
							messageData.getSignature()));
		}
	}

	@Test
	public void testResolveKeyRequestsReportsHistoricalNoKeyAndInvalidRequests() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-resolve-invalid");
			PrivateGroupChatService.getInstance().requestKey(repository, fixture.bob.getPrivateKey(),
					fixture.groupId, bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 90));
			addMember(repository, fixture.groupId, fixture.chloe);

			PrivateGroupChatMembership.MembershipEpoch currentEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);
			PrivateGroupChatEnvelope mismatchedSenderRequest = PrivateGroupChatKeyRequest.create(currentEpoch,
					fixture.bob.getPrivateKey(), bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 91));
			ChatTransactionData invalidRequestData = signedChat(repository, fixture.alice, fixture.groupId,
					mismatchedSenderRequest.toBytes(), false, true, now() + 1000);
			repository.getChatStoreRepository().save(invalidRequestData);
			repository.saveChanges();

			List<PrivateGroupChatService.KeyRequestRecoveryResult> results = PrivateGroupChatService.getInstance()
					.resolveKeyRequests(repository, fixture.alice.getPrivateKey(), fixture.groupId, null);

			assertEquals(2, results.size());
			assertEquals(1, countRecoveryStatus(results,
					PrivateGroupChatService.KeyRequestRecoveryStatus.NO_KEY_AVAILABLE));
			assertEquals(1, countRecoveryStatus(results,
					PrivateGroupChatService.KeyRequestRecoveryStatus.INVALID_REQUEST));
		}
	}

	@Test
	public void testRotateKeyStoresFreshAnnouncementAndFutureSendUsesIt() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-local-rotate");
			byte[] firstPlaintext = bytes("before rotation");
			PrivateGroupChatService.SendResult firstResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, firstPlaintext, true, null);

			PrivateGroupChatService.KeyRotationResult rotationResult = PrivateGroupChatService.getInstance().rotateKey(repository,
					fixture.alice.getPrivateKey(), fixture.groupId);

			assertNotNull(rotationResult.getKeyAnnouncementSignature());
			assertArrayEquals(firstResult.getEpochId(), rotationResult.getEpochId());
			assertFalse(Arrays.equals(firstResult.getKeyId(), rotationResult.getKeyId()));

			ChatTransactionData rotationAnnouncementData = repository.getChatStoreRepository().fromSignature(
					rotationResult.getKeyAnnouncementSignature());
			assertNotNull(rotationAnnouncementData);
			assertEquals(fixture.alice.getAddress(), rotationAnnouncementData.getSender());
			assertFalse(rotationAnnouncementData.getIsText());
			assertTrue(rotationAnnouncementData.getIsEncrypted());

			PrivateGroupChatService.SendResult secondResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("after rotation"), true,
					firstResult.getMessageSignature());
			assertNull(secondResult.getKeyAnnouncementSignature());
			assertArrayEquals(rotationResult.getEpochId(), secondResult.getEpochId());
			assertArrayEquals(rotationResult.getKeyId(), secondResult.getKeyId());

			PrivateGroupChatService.DecryptResult oldDecryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), firstResult.getMessageSignature());
			assertArrayEquals(firstPlaintext, oldDecryptResult.getData());
		}
	}

	@Test
	public void testRotateKeyRejectsNonMember() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "priv-local-rot-nonmember");

			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatService.getInstance().rotateKey(repository,
							fixture.chloe.getPrivateKey(), fixture.groupId));
		}
	}

	@Test
	public void testRequestRotationStoresRotationRequestEnvelope() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-rotation-request");
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);

			PrivateGroupChatService.RotationRequestResult result = PrivateGroupChatService.getInstance()
					.requestRotation(repository, fixture.alice.getPrivateKey(), fixture.groupId);

			assertNotNull(result.getRequestSignature());
			assertArrayEquals(epoch.getEpochId(), result.getEpochId());

			ChatTransactionData rotationRequestData = repository.getChatStoreRepository().fromSignature(
					result.getRequestSignature());
			assertNotNull(rotationRequestData);
			assertEquals(fixture.groupId, rotationRequestData.getTxGroupId());
			assertEquals(fixture.alice.getAddress(), rotationRequestData.getSender());
			assertFalse(rotationRequestData.getIsText());
			assertTrue(rotationRequestData.getIsEncrypted());

			PrivateGroupChatEnvelope rotationRequest = PrivateGroupChatEnvelope.fromBytes(rotationRequestData.getData());
			assertEquals(PrivateGroupChatEnvelope.Type.ROTATION_REQUEST, rotationRequest.getType());
			assertArrayEquals(fixture.alice.getPublicKey(), rotationRequest.getRequesterPublicKey());
		}
	}

	@Test
	public void testRequestRotationAllowsAdminAndRejectsOrdinaryMember() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "priv-rot-req-admin");
			addAdmin(repository, fixture.groupId, fixture.bob);
			addMember(repository, fixture.groupId, fixture.chloe);

			PrivateGroupChatService.RotationRequestResult result = PrivateGroupChatService.getInstance()
					.requestRotation(repository, fixture.bob.getPrivateKey(), fixture.groupId);
			assertNotNull(result.getRequestSignature());

			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatService.getInstance().requestRotation(repository,
							fixture.chloe.getPrivateKey(), fixture.groupId));
		}
	}

	@Test
	public void testAcceptedRotationRequestForcesFreshKeyOnNextSend() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "priv-rot-req-fresh");
			byte[] oldPlaintext = bytes("before owner request");
			PrivateGroupChatService.SendResult firstResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, oldPlaintext, true, null);

			PrivateGroupChatService.RotationRequestResult rotationRequestResult = PrivateGroupChatService.getInstance()
					.requestRotation(repository, fixture.alice.getPrivateKey(), fixture.groupId);
			assertNotNull(rotationRequestResult.getRequestSignature());

			byte[] newPlaintext = bytes("after owner request");
			PrivateGroupChatService.SendResult secondResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.bob.getPrivateKey(), fixture.groupId, newPlaintext, true, firstResult.getMessageSignature());

			assertNotNull(secondResult.getKeyAnnouncementSignature());
			assertArrayEquals(firstResult.getEpochId(), secondResult.getEpochId());
			assertFalse(Arrays.equals(firstResult.getKeyId(), secondResult.getKeyId()));

			PrivateGroupChatService.DecryptResult oldDecryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), firstResult.getMessageSignature());
			assertArrayEquals(oldPlaintext, oldDecryptResult.getData());

			PrivateGroupChatService.DecryptResult newDecryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.alice.getPrivateKey(), secondResult.getMessageSignature());
			assertArrayEquals(newPlaintext, newDecryptResult.getData());
		}
	}

	@Test
	public void testOrdinaryMemberRotationRequestDoesNotAffectNextSend() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "priv-rot-req-member-skip");
			PrivateGroupChatService.SendResult firstResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("before member request"), true, null);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);
			PrivateGroupChatEnvelope ordinaryMemberRequest = PrivateGroupChatRotationRequest.create(epoch,
					fixture.bob.getPrivateKey());
			ChatTransactionData ordinaryMemberRequestData = signedChat(repository, fixture.bob, fixture.groupId,
					ordinaryMemberRequest.toBytes(), false, true, now() + 1000);
			repository.getChatStoreRepository().save(ordinaryMemberRequestData);
			repository.saveChanges();

			PrivateGroupChatService.SendResult secondResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("after ignored member request"), true,
					firstResult.getMessageSignature());

			assertNull(secondResult.getKeyAnnouncementSignature());
			assertArrayEquals(firstResult.getEpochId(), secondResult.getEpochId());
			assertArrayEquals(firstResult.getKeyId(), secondResult.getKeyId());
		}
	}

	@Test
	public void testDecryptRehydratesCachedKeyFromStoredAnnouncement() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-missing-key");
			byte[] plaintext = bytes("secret");
			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, plaintext, true, null);

			PrivateGroupChatKeyCache.getInstance().clear();

			PrivateGroupChatService.DecryptResult decryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), sendResult.getMessageSignature());

			assertArrayEquals(plaintext, decryptResult.getData());
			assertArrayEquals(sendResult.getKeyId(), decryptResult.getKeyId());
			assertNotNull(PrivateGroupChatKeyCache.getInstance().get(fixture.groupId,
					sendResult.getEpochId(), sendResult.getKeyId()));
		}
	}

	@Test
	public void testDecryptStillFailsWithoutStoredAnnouncement() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-no-announcement");
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 10);
			byte[] keyId = PrivateGroupChatCrypto.computeKeyId(fixture.groupId, epoch.getEpochId(), groupKey);
			byte[] nonce = PrivateGroupChatCrypto.generateNonce();
			byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, fixture.groupId, epoch.getEpochId(),
					keyId, nonce, bytes("secret"));
			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(fixture.groupId,
					epoch.getEpochId(), keyId, nonce, ciphertext);
			ChatTransactionData messageData = signedChat(repository, fixture.alice, fixture.groupId,
					messageEnvelope.toBytes(), true, true, now());

			assertEquals(ValidationResult.OK, ChatService.getInstance().validateAndStore(repository, messageData));
			PrivateGroupChatKeyCache.getInstance().clear();

			assertThrows(PrivateGroupChatService.PrivateGroupChatException.class,
					() -> PrivateGroupChatService.getInstance().decrypt(repository, fixture.bob.getPrivateKey(),
							messageData.getSignature()));
		}
	}

	@Test
	public void testHistoricalMemberCanDecryptOldMessageAfterMembershipChange() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "priv-history-decrypt");
			byte[] plaintext = bytes("old epoch secret");

			PrivateGroupChatService.SendResult oldResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, plaintext, true, null);
			PrivateGroupChatKeyCache.getInstance().clear();

			addMember(repository, fixture.groupId, fixture.chloe);
			PrivateGroupChatMembership.MembershipEpoch currentEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);
			assertFalse(Arrays.equals(oldResult.getEpochId(), currentEpoch.getEpochId()));

			PrivateGroupChatService.DecryptResult decryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), oldResult.getMessageSignature());
			assertArrayEquals(plaintext, decryptResult.getData());
			assertArrayEquals(oldResult.getEpochId(), decryptResult.getEpochId());
			assertArrayEquals(oldResult.getKeyId(), decryptResult.getKeyId());

			assertThrows(PrivateGroupChatService.PrivateGroupChatException.class,
					() -> PrivateGroupChatService.getInstance().decrypt(repository, fixture.chloe.getPrivateKey(),
							oldResult.getMessageSignature()));
		}
	}

	@Test
	public void testSendAfterMembershipChangeUsesNewEpoch() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-new-epoch");
			PrivateGroupChatService.SendResult oldResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("old message"), true, null);

			addMember(repository, fixture.groupId, fixture.chloe);
			byte[] plaintext = bytes("new epoch message");
			PrivateGroupChatService.SendResult newResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, plaintext, true, oldResult.getMessageSignature());
			PrivateGroupChatMembership.MembershipEpoch currentEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);

			assertNotNull(newResult.getKeyAnnouncementSignature());
			assertFalse(Arrays.equals(oldResult.getEpochId(), newResult.getEpochId()));
			assertArrayEquals(currentEpoch.getEpochId(), newResult.getEpochId());

			PrivateGroupChatService.DecryptResult bobDecryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), newResult.getMessageSignature());
			assertArrayEquals(plaintext, bobDecryptResult.getData());

			PrivateGroupChatService.DecryptResult chloeDecryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.chloe.getPrivateKey(), newResult.getMessageSignature());
			assertArrayEquals(plaintext, chloeDecryptResult.getData());
		}
	}

	@Test
	public void testRehydrateIgnoresUnusableAnnouncementsBeforeValidAnnouncement() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "priv-noisy-announce");
			byte[] plaintext = bytes("secret");
			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, plaintext, true, null);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);

			ChatTransactionData malformedData = rawChat(fixture.alice, fixture.groupId, bytes("not an envelope"),
					false, true, now() + 10, signature(61));
			byte[] unrelatedGroupKey = bytes(Transformer.AES256_LENGTH, 70);
			PrivateGroupChatEnvelope unrelatedAnnouncement = PrivateGroupChatKeyAnnouncement.create(epoch,
					unrelatedGroupKey, fixture.alice.getPrivateKey());
			ChatTransactionData unrelatedAnnouncementData = rawChat(fixture.alice, fixture.groupId,
					unrelatedAnnouncement.toBytes(), false, true, now() + 11, signature(62));
			repository.getChatStoreRepository().save(malformedData);
			repository.getChatStoreRepository().save(unrelatedAnnouncementData);
			repository.saveChanges();
			PrivateGroupChatKeyCache.getInstance().clear();

			PrivateGroupChatService.DecryptResult decryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), sendResult.getMessageSignature());

			assertArrayEquals(plaintext, decryptResult.getData());
			assertArrayEquals(sendResult.getKeyId(), decryptResult.getKeyId());
		}
	}

	@Test
	public void testDefensiveCopies() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-copies");
			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("secret"), true, null);

			byte[] epochId = sendResult.getEpochId();
			byte[] keyId = sendResult.getKeyId();
			byte[] expectedEpochId = epochId.clone();
			byte[] expectedKeyId = keyId.clone();
			epochId[0] ^= 1;
			keyId[0] ^= 1;

			PrivateGroupChatService.DecryptResult decryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), sendResult.getMessageSignature());

			assertArrayEquals(expectedEpochId, decryptResult.getEpochId());
			assertArrayEquals(expectedKeyId, decryptResult.getKeyId());
			assertArrayEquals(bytes("secret"), decryptResult.getData());
		}
	}

	private static Fixture createFixture(Repository repository, String groupName) throws DataException {
		TestAccount alice = Common.getTestAccount(repository, "alice");
		TestAccount bob = Common.getTestAccount(repository, "bob");
		TestAccount chloe = Common.getTestAccount(repository, "chloe");

		int groupId = GroupUtils.createGroup(repository, alice, groupName, false, ApprovalThreshold.ONE, 10, 40);
		addMember(repository, groupId, bob);

		return new Fixture(alice, bob, chloe, groupId);
	}

	private static void addMember(Repository repository, int groupId, TestAccount account) throws DataException {
		account.ensureAccount();

		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		repository.getGroupRepository().save(new GroupMemberData(groupId, account.getAddress(),
				groupData.getCreated(), groupData.getReference()));
		repository.saveChanges();
	}

	private static void addAdmin(Repository repository, int groupId, TestAccount account) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		repository.getGroupRepository().save(new GroupAdminData(groupId, account.getAddress(),
				groupData.getReference()));
		repository.saveChanges();
	}

	private static ChatTransactionData signedChat(Repository repository, TestAccount sender, int groupId,
			byte[] data, boolean isText, boolean isEncrypted, long timestamp) throws DataException {
		ChatTransactionData chatData = rawChat(sender, groupId, data, isText, isEncrypted, timestamp, null);
		ChatTransaction chatTransaction = new ChatTransaction(repository, chatData);
		chatTransaction.computeNonce();
		chatTransaction.sign(sender);
		return chatData;
	}

	private static ChatTransactionData rawChat(TestAccount sender, int groupId, byte[] data, boolean isText,
			boolean isEncrypted, long timestamp, byte[] signature) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				groupId,
				sender.getPublicKey(),
				0L,
				0,
				signature);

		return new ChatTransactionData(baseTransactionData, sender.getAddress(), 0, null, null,
				data, isText, isEncrypted);
	}

	private static long now() {
		Long now = NTP.getTime();
		return now != null ? now : System.currentTimeMillis();
	}

	private static byte[] bytes(String message) {
		return message.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] bytes(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < bytes.length; ++i)
			bytes[i] = (byte) (seed + i);

		return bytes;
	}

	private static byte[] signature(int value) {
		byte[] signature = new byte[64];
		signature[63] = (byte) value;
		return signature;
	}

	private static int countRecoveryStatus(List<PrivateGroupChatService.KeyRequestRecoveryResult> results,
			PrivateGroupChatService.KeyRequestRecoveryStatus status) {
		int count = 0;
		for (PrivateGroupChatService.KeyRequestRecoveryResult result : results)
			if (result.getStatus() == status)
				++count;

		return count;
	}

	private static class Fixture {
		private final TestAccount alice;
		private final TestAccount bob;
		private final TestAccount chloe;
		private final int groupId;

		private Fixture(TestAccount alice, TestAccount bob, TestAccount chloe, int groupId) {
			this.alice = alice;
			this.bob = bob;
			this.chloe = chloe;
			this.groupId = groupId;
		}
	}

}
