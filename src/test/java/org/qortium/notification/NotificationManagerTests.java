package org.qortium.notification;

import org.eclipse.jetty.ee8.websocket.api.Session;
import org.junit.After;
import org.junit.Test;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.JoinGroupTransactionData;
import org.qortium.data.transaction.PaymentTransactionData;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.transaction.Transaction;
import org.qortium.test.common.Common;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NotificationManagerTests extends Common {

    private static final String BITCOIN_TEST_XPUB =
            "tpubDCxs3oB9X7XJYkQGU6gfPwd4h3NEiBGA8mfD1aEbZiG5x3BTH4cJqszDP6dtoHPPjZNEj5jPxuSWHCvjg9AHz4dNg6w5vQhv1B8KwWKpxoz";
    private static final String BITCOIN_TEST_XPRV =
            "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ";

    private final NotificationManager notificationManager = NotificationManager.getInstance();
    private final List<Session> sessions = new ArrayList<>();

    @After
    public void afterTest() {
        NotificationManager.resetNotificationTimeSupplierForTesting();
        for (Session session : this.sessions) {
            this.notificationManager.onSessionClose(session);
        }
    }

    @Test
    public void testChatMessageFiltersMatchAndReject() {
        CapturingSession recipientMatch = subscribe("CHAT_MESSAGE", Map.of("recipient", "recipient"));
        CapturingSession recipientMiss = subscribe("CHAT_MESSAGE", Map.of("recipient", "other"));
        CapturingSession senderMatch = subscribe("CHAT_MESSAGE", Map.of("sender", "sender"));
        CapturingSession senderMiss = subscribe("CHAT_MESSAGE", Map.of("sender", "other"));
        CapturingSession groupMatch = subscribe("CHAT_MESSAGE", Map.of("txGroupId", "123"));
        CapturingSession groupMiss = subscribe("CHAT_MESSAGE", Map.of("txGroupId", "456"));
        CapturingSession involvingMatch = subscribe("CHAT_MESSAGE", Map.of("involving", "RECIPIENT"));
        CapturingSession involvingMiss = subscribe("CHAT_MESSAGE", Map.of("involving", "other"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sender", "sender");
        data.put("recipient", "recipient");
        data.put("txGroupId", 123);
        data.put("isText", true);
        data.put("isEncrypted", false);
        data.put("signature", "chat-signature");
        data.put("created", 123456L);
        this.notificationManager.processEvent(new NotificationEvent(
                "CHAT_MESSAGE", data, "chat-signature", Arrays.asList("sender", "recipient")));

        Map<String, Object> groupData = new LinkedHashMap<>();
        groupData.put("sender", "group-sender");
        groupData.put("txGroupId", 123);
        groupData.put("isText", true);
        groupData.put("isEncrypted", false);
        groupData.put("signature", "group-signature");
        groupData.put("created", 123457L);
        this.notificationManager.processEvent(new NotificationEvent(
                "CHAT_MESSAGE", groupData, "group-signature", List.of("group-sender")));

        assertNotificationCount(1, recipientMatch, senderMatch, groupMatch, involvingMatch);
        assertNotificationCount(0, recipientMiss, senderMiss, groupMiss, involvingMiss);
        assertTrue(recipientMatch.notifications.get(0).contains("\"isText\":true"));
        assertTrue(recipientMatch.notifications.get(0).contains("\"txGroupId\":123"));
    }

    @Test
    public void testTransactionConfirmedFiltersMatchAndReject() {
        CapturingSession signatureMatch = subscribe("TRANSACTION_CONFIRMED", Map.of("signature", "tx-signature"));
        CapturingSession signatureMiss = subscribe("TRANSACTION_CONFIRMED", Map.of("signature", "other"));
        CapturingSession addressMatch = subscribe("TRANSACTION_CONFIRMED", Map.of("address", "recipient"));
        CapturingSession addressMiss = subscribe("TRANSACTION_CONFIRMED", Map.of("address", "other"));
        CapturingSession comboMatch = subscribe("TRANSACTION_CONFIRMED", Map.of("address", "sender", "txType", "PAYMENT"));
        CapturingSession comboMiss = subscribe("TRANSACTION_CONFIRMED", Map.of("address", "sender", "txType", "ARBITRARY"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "PAYMENT");
        data.put("signature", "tx-signature");
        data.put("sender", "sender");
        data.put("recipients", List.of("recipient"));
        data.put("blockHeight", 99);
        data.put("created", 123456L);
        this.notificationManager.processEvent(new NotificationEvent(
                "TRANSACTION_CONFIRMED", data, "tx-signature", Arrays.asList("sender", "recipient")));

        assertNotificationCount(1, signatureMatch, addressMatch, comboMatch);
        assertNotificationCount(0, signatureMiss, addressMiss, comboMiss);
        assertTrue(signatureMatch.notifications.get(0).contains("\"recipients\":[\"recipient\"]"));
        assertTrue(signatureMatch.notifications.get(0).contains("\"blockHeight\":99"));
    }

    @Test
    public void testTransactionConfirmedArrayFiltersMatchAndReject() {
        CapturingSession txTypeMatch = subscribe("TRANSACTION_CONFIRMED",
                Map.of("signature", "tx-signature", "txType", List.of("PAYMENT", "TRANSFER_ASSET")));
        CapturingSession txTypeMiss = subscribe("TRANSACTION_CONFIRMED",
                Map.of("signature", "tx-signature", "txType", List.of("ARBITRARY", "MESSAGE")));
        CapturingSession scalarMatch = subscribe("TRANSACTION_CONFIRMED",
                Map.of("signature", "tx-signature", "txType", "PAYMENT"));
        CapturingSession singleValueArrayMatch = subscribe("TRANSACTION_CONFIRMED",
                Map.of("signature", "tx-signature", "txType", List.of("PAYMENT")));
        CapturingSession combinedMatch = subscribe("TRANSACTION_CONFIRMED",
                Map.of("address", "sender", "txType", List.of("PAYMENT", "TRANSFER_ASSET")));
        CapturingSession combinedMiss = subscribe("TRANSACTION_CONFIRMED",
                Map.of("address", "other", "txType", List.of("PAYMENT", "TRANSFER_ASSET")));
        CapturingSession addressArrayMatch = subscribe("TRANSACTION_CONFIRMED",
                Map.of("address", List.of("other", "recipient")));

        Map<String, Object> data = Map.of("type", "PAYMENT", "signature", "tx-signature", "sender", "sender",
                "recipients", List.of("recipient"), "blockHeight", 99, "created", 123456L);
        this.notificationManager.processEvent(new NotificationEvent(
                "TRANSACTION_CONFIRMED", data, "tx-signature", Arrays.asList("sender", "recipient")));

        assertNotificationCount(1, txTypeMatch, scalarMatch, singleValueArrayMatch, combinedMatch, addressArrayMatch);
        assertNotificationCount(0, txTypeMiss, combinedMiss);
    }

    @Test
    public void testTransactionConfirmedGroupIdFilterMatchesWithoutAddressAnchor() throws Exception {
        CapturingSession groupMatch = subscribe("TRANSACTION_CONFIRMED",
                Map.of("txType", "JOIN_GROUP", "groupId", "123"));
        CapturingSession groupMiss = subscribe("TRANSACTION_CONFIRMED",
                Map.of("txType", "JOIN_GROUP", "groupId", "456"));
        long now = 1_000_000L;
        NotificationManager.setNotificationTimeSupplierForTesting(() -> now);

        this.notificationManager.onTransactionConfirmed(new NotificationTestTransaction(
                new JoinGroupTransactionData(baseTransactionData(), 123)), 99, now - 1L);

        awaitNotificationCount(1, groupMatch);
        assertNotificationCount(0, groupMiss);
        assertTrue(groupMatch.notifications.get(0).contains("\"groupId\":123"));
    }

    @Test
    public void testSubscriptionValidationRejectsUnknownEventsAndFilters() {
        assertValidationError(subscription("UNKNOWN", Map.of("recipient", "recipient")), "Unknown notification event");
        assertValidationError(subscription("CHAT_MESSAGE", Map.of()), "require at least one filter");
        assertValidationError(subscription("TRANSACTION_CONFIRMED", Map.of("txType", "PAYMENT")),
                "require a signature, address, or groupId");
        assertNull(NotificationManager.validateSubscription(subscription("TRANSACTION_CONFIRMED",
                Map.of("txType", "JOIN_GROUP", "groupId", "123"))));
        assertValidationError(subscription("CHAT_MESSAGE", Map.of("content", "secret")), "Unknown filter key");
        assertNull(NotificationManager.validateSubscription(subscription("PAYMENT_RECEIVED", Map.of("recipient", "recipient"))));
    }

    @Test
    public void testSubscriptionValidationRejectsInvalidFilterArrays() {
        assertValidationError(subscription("CHAT_MESSAGE", Map.of("sender", List.of())), "must not be empty");
        assertValidationError(subscription("CHAT_MESSAGE", Map.of("sender", Arrays.asList("sender", ""))),
                "only non-blank strings");
        assertValidationError(subscription("CHAT_MESSAGE", Map.of("sender", Arrays.asList("sender", 1))),
                "only non-blank strings");
        assertValidationError(subscription("RESOURCE_PUBLISHED", Map.of("service", List.of("APP", "WEBSITE"))),
                "not supported for RESOURCE_PUBLISHED");
    }

    @Test
    public void testForeignPaymentSubscriptionValidation() {
        assertNull(NotificationManager.validateSubscription(subscription("FOREIGN_PAYMENT_RECEIVED",
                Map.of("coin", "BTC", "xpub", BITCOIN_TEST_XPUB))));
        assertValidationError(subscription("FOREIGN_PAYMENT_RECEIVED", Map.of("coin", "UNKNOWN", "xpub", "invalid")),
                "Unsupported ElectrumX coin");
        assertValidationError(subscription("FOREIGN_PAYMENT_RECEIVED", Map.of("coin", "BTC")), "require a non-blank xpub");
        assertValidationError(subscription("FOREIGN_PAYMENT_RECEIVED", Map.of("coin", "BTC", "xpub", "invalid")),
                "Invalid xpub");
        assertValidationError(subscription("FOREIGN_PAYMENT_RECEIVED",
                Map.of("coin", "BTC", "xpub", BITCOIN_TEST_XPRV)), "requires a public extended key");
        assertValidationError(subscription("FOREIGN_PAYMENT_RECEIVED",
                Map.of("coin", List.of("BTC"), "xpub", BITCOIN_TEST_XPUB)), "must be a string");
        assertValidationError(subscription("FOREIGN_PAYMENT_RECEIVED",
                Map.of("coin", "BTC", "xpub", "x".repeat(ForeignPaymentNotificationService.MAX_XPUB_LENGTH + 1))),
                "character limit");
    }

    @Test
    public void testRepeatedSignatureIsDeduplicatedPerSession() {
        CapturingSession session = subscribe("CHAT_MESSAGE", Map.of("sender", "sender"));
        Map<String, Object> data = Map.of("sender", "sender", "txGroupId", 0, "isText", true,
                "isEncrypted", false, "signature", "same-signature", "created", 123456L);
        NotificationEvent event = new NotificationEvent("CHAT_MESSAGE", data, "same-signature", List.of("sender"));

        this.notificationManager.processEvent(event);
        this.notificationManager.processEvent(event);

        assertNotificationCount(1, session);
    }

    @Test
    public void testHistoryIgnoresEventsWithoutV1History() {
        CapturingSession session = subscribe("CHAT_MESSAGE", Map.of("sender", "sender"));

        this.notificationManager.handleNotificationHistory(session.session, null, null, null);

        assertNotificationCount(1, session);
        assertEquals("{\"type\":\"history\",\"results\":[]}", session.notifications.get(0));
    }

    @Test
    public void testConfirmedTransactionDispatchUsesBlockRecency() throws Exception {
        CapturingSession session = subscribe("TRANSACTION_CONFIRMED", Map.of("address", "sender"));
        Transaction transaction = confirmedTransaction();
        long now = 1_000_000L;
        NotificationManager.setNotificationTimeSupplierForTesting(() -> now);

        this.notificationManager.onTransactionConfirmed(transaction, 99, now - 1L);
        awaitNotificationCount(1, session);

        this.notificationManager.onTransactionConfirmed(transaction, 100,
                now - NotificationManager.NOTIFICATION_RECENCY_WINDOW_MS - 1L);
        assertNotificationCount(1, session);
    }

    @Test
    public void testConfirmedTransactionPayloadIncludesTypeSpecificExtras() throws Exception {
        CapturingSession paymentSession = subscribe("TRANSACTION_CONFIRMED",
                Map.of("address", "sender", "txType", "PAYMENT"));
        CapturingSession nameSession = subscribe("TRANSACTION_CONFIRMED",
                Map.of("address", "sender", "txType", "REGISTER_NAME"));
        CapturingSession ratingSession = subscribe("TRANSACTION_CONFIRMED",
                Map.of("address", "sender", "txType", "RATE_ACCOUNT"));
        long now = 1_000_000L;
        NotificationManager.setNotificationTimeSupplierForTesting(() -> now);

        this.notificationManager.onTransactionConfirmed(new NotificationTestTransaction(
                new PaymentTransactionData(baseTransactionData(), "recipient", 123456789L)), 99, now - 1L);
        awaitNotificationCount(1, paymentSession);
        assertTrue(paymentSession.notifications.get(0).contains("\"amount\":\"1.23456789\""));

        this.notificationManager.onTransactionConfirmed(new NotificationTestTransaction(
                new RegisterNameTransactionData(baseTransactionData(), "notification-name", "{}")), 99, now - 1L);
        awaitNotificationCount(1, nameSession);
        assertTrue(nameSession.notifications.get(0).contains("\"name\":\"notification-name\""));

        this.notificationManager.onTransactionConfirmed(new NotificationTestTransaction(
                new RateAccountTransactionData(baseTransactionData(), new byte[32], 4)), 99, now - 1L);
        awaitNotificationCount(1, ratingSession);
        assertTrue(ratingSession.notifications.get(0).contains("\"rating\":4"));
    }

    @Test
    public void testGenericNotificationEchoesAppName() {
        NotificationSubscription rule = subscription("CHAT_MESSAGE", Map.of("sender", "sender"));
        rule.setAppName("Home");
        CapturingSession session = subscribe(rule);

        this.notificationManager.processEvent(new NotificationEvent(
                "CHAT_MESSAGE", Map.of("sender", "sender", "txGroupId", 0, "isText", true,
                        "isEncrypted", false, "created", 123456L), "chat-signature", List.of("sender")));

        assertNotificationCount(1, session);
        assertTrue(session.notifications.get(0).contains("\"appName\":\"Home\""));
    }

    private CapturingSession subscribe(String event, Map<String, ?> filters) {
        return subscribe(subscription(event, filters));
    }

    private CapturingSession subscribe(NotificationSubscription rule) {
        CapturingSession capturingSession = new CapturingSession("notification-test-" + this.sessions.size());
        this.sessions.add(capturingSession.session);
        this.notificationManager.onSessionOpen(capturingSession.session, null);
        this.notificationManager.setSubscriptions(capturingSession.session, List.of(rule));
        return capturingSession;
    }

    private static NotificationSubscription subscription(String event, Map<String, ?> filters) {
        NotificationSubscription rule = new NotificationSubscription();
        rule.setEvent(event);
        rule.setNotificationId("notification-id");
        rule.setFilters(filters);
        return rule;
    }

    private static void assertValidationError(NotificationSubscription rule, String expectedText) {
        String error = NotificationManager.validateSubscription(rule);
        assertTrue(error != null && error.contains(expectedText));
    }

    private static void assertNotificationCount(int expectedCount, CapturingSession... sessions) {
        for (CapturingSession session : sessions) {
            assertEquals(expectedCount, session.notifications.size());
        }
    }

    private static void awaitNotificationCount(int expectedCount, CapturingSession session) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 1000L;
        while (session.notifications.size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertNotificationCount(expectedCount, session);
    }

    private static Transaction confirmedTransaction() {
        return new NotificationTestTransaction(new PaymentTransactionData(baseTransactionData(), "recipient", 1L));
    }

    private static BaseTransactionData baseTransactionData() {
        byte[] creatorPublicKey = new byte[32];
        byte[] signature = new byte[64];
        signature[0] = 1;
        return new BaseTransactionData(123456L, 0, creatorPublicKey, 0L, signature);
    }

    private static class NotificationTestTransaction extends Transaction {
        private NotificationTestTransaction(TransactionData transactionData) {
            super(null, transactionData);
        }

        @Override
        public List<String> getRecipientAddresses() {
            return List.of("recipient");
        }

        @Override
        public List<String> getInvolvedAddresses() {
            return List.of("sender", "recipient");
        }

        @Override
        public ValidationResult isValid() {
            return ValidationResult.OK;
        }

        @Override
        public void process() {
            // Not needed for notification dispatch tests.
        }

        @Override
        public void orphan() {
            // Not needed for notification dispatch tests.
        }
    }

    private static class CapturingSession {
        private final List<String> notifications = new CopyOnWriteArrayList<>();
        private final Session session;

        private CapturingSession(String name) {
            this.session = (Session) Proxy.newProxyInstance(
                    NotificationManagerTests.class.getClassLoader(),
                    new Class[] { Session.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isOpen":
                                return true;
                            case "getRemote":
                                return Proxy.newProxyInstance(
                                        NotificationManagerTests.class.getClassLoader(),
                                        new Class[] { method.getReturnType() },
                                        (remoteProxy, remoteMethod, remoteArgs) -> {
                                            if ("sendString".equals(remoteMethod.getName())) {
                                                this.notifications.add((String) remoteArgs[0]);
                                            }
                                            return defaultValue(remoteMethod.getReturnType());
                                        });
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            case "toString":
                                return name;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private static Object defaultValue(Class<?> returnType) {
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            return null;
        }
    }
}
