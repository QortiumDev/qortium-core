package org.qortium.api.resource;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortium.api.Security;
import org.qortium.api.model.crosschain.PirateChainSendRequest;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Versioned transport facade. Native resources retain parsing, ownership and send admission. */
@Path("/crosschain/wallets/{coin}")
@Tag(name = "Cross-Chain Wallets")
@Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
public class CrossChainWalletResource {
    public static final String CONTRACT = "qortium-core-wallet-api-v1";
    public static final String SESSION_HEADER = "X-WALLET-SESSION";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @Context HttpServletRequest request;

    interface Adapter {
        Set<String> reads();
        Set<String> writes();
        Object read(String operation, String key, String session, String id);
        Object write(String operation, String key, String session, String id, InputStream body) throws IOException;
    }

    private Adapter adapter(String coin, String key) {
        // Must run before any body consumption, runtime creation or backend selection.
        Security.checkApiCallAllowed(request, key);
        Security.requireLoopbackRequest(request);
        return switch (coin) {
            case "XMR" -> monero();
            case "ARRR" -> pirate();
            default -> throw new NotFoundException();
        };
    }

    @GET @Path("/{operation}")
    public Response read(@PathParam("coin") String coin, @PathParam("operation") String operation,
                         @HeaderParam(Security.API_KEY_HEADER) String key,
                         @HeaderParam(SESSION_HEADER) String session) {
        Adapter adapter = adapter(coin, key);
        if ("protocol".equals(operation))
            return protocol(coin, adapter);
        if (!adapter.reads().contains(operation)) throw new NotFoundException();
        return response(adapter.read(operation, key, session, null));
    }

    @GET @Path("/send/status/{operationId}")
    public Response sendStatus(@PathParam("coin") String coin, @PathParam("operationId") String id,
                               @HeaderParam(Security.API_KEY_HEADER) String key,
                               @HeaderParam(SESSION_HEADER) String session) {
        Adapter adapter = adapter(coin, key);
        if (!adapter.reads().contains("send/status")) throw new NotFoundException();
        return response(adapter.read("send/status", key, session, id));
    }

    @POST @Path("/{operation: [a-z-]+(?:/[a-z-]+)?}")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    public Response write(@PathParam("coin") String coin, @PathParam("operation") String operation,
                          @HeaderParam(Security.API_KEY_HEADER) String key,
                          @HeaderParam(SESSION_HEADER) String session, InputStream body) throws IOException {
        Adapter adapter = adapter(coin, key);
        if (!adapter.writes().contains(operation)) throw new NotFoundException();
        requireContentType(coin, operation);
        return response(adapter.write(operation, key, session, null, body));
    }

    // Explicit no-body routes also accept callers with no Content-Type header.
    @POST @Path("/stop")
    public Response stop(@PathParam("coin") String coin, @HeaderParam(Security.API_KEY_HEADER) String key,
                         @HeaderParam(SESSION_HEADER) String session) throws IOException {
        Adapter adapter = adapter(coin, key);
        if (!adapter.writes().contains("stop")) throw new NotFoundException();
        return response(adapter.write("stop", key, session, null, null));
    }

    @POST @Path("/start")
    public Response start(@PathParam("coin") String coin, @HeaderParam(Security.API_KEY_HEADER) String key) throws IOException {
        Adapter adapter = adapter(coin, key);
        if (!adapter.writes().contains("start")) throw new NotFoundException();
        return response(adapter.write("start", key, null, null, null));
    }

    @POST @Path("/send/lookup/{idempotencyKey}") @Consumes(MediaType.TEXT_PLAIN)
    public Response sendLookup(@PathParam("coin") String coin, @PathParam("idempotencyKey") String id,
                               @HeaderParam(Security.API_KEY_HEADER) String key, InputStream body) throws IOException {
        Adapter adapter = adapter(coin, key);
        if (!adapter.writes().contains("send/lookup")) throw new NotFoundException();
        return response(adapter.write("send/lookup", key, null, id, body));
    }

    private void requireContentType(String coin, String operation) {
        boolean plain = coin.equals("ARRR") && Set.of("status", "address", "balance", "transactions", "send/readiness").contains(operation);
        // Stop/start carry no body. XMR's strict readers still validate all JSON commands.
        if (Set.of("start", "stop").contains(operation)) return;
        String expected = plain ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON;
        String actual = request.getContentType();
        if (actual == null || !MediaType.valueOf(actual).isCompatible(MediaType.valueOf(expected)))
            throw new NotSupportedException();
    }

    private Response protocol(String coin, Adapter adapter) {
        try { return Response.ok(JSON.writeValueAsString(Map.of("contract", CONTRACT, "coin", coin,
                "reads", adapter.reads(), "writes", adapter.writes(), "sessionHeader", SESSION_HEADER)),
                MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").build(); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new InternalServerErrorException(); }
    }

    private boolean verified() {
        String value = request.getParameter("verified");
        if (value == null || value.equals("false")) return false;
        if (value.equals("true")) return true;
        throw malformed();
    }

    private Response response(Object value) {
        if (value instanceof Response nativeResponse) return nativeResponse;
        // Preserve native text, especially exact atomic amounts. Never relabel them as JSON numbers.
        return Response.ok(value, value instanceof String ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store").build();
    }

    private Adapter monero() {
        var resource = new CrossChainMoneroResource(); resource.request = request;
        return new Adapter() {
            public Set<String> reads() { return Set.of("capabilities", "session", "status", "servers", "send/status"); }
            public Set<String> writes() { return Set.of("activate", "stop", "send/prepare", "send/commit", "send/cancel", "send/reconcile"); }
            public Object read(String op, String key, String session, String id) {
                return switch (op) {
                    case "capabilities" -> resource.capabilities(key);
                    case "session" -> resource.session(key);
                    case "status" -> resource.wallet(key, session);
                    case "servers" -> resource.servers(key, session);
                    case "send/status" -> resource.sendStatus(key, session, id);
                    default -> throw new NotFoundException();
                };
            }
            public Object write(String op, String key, String session, String id, InputStream body) {
                return switch (op) {
                    case "activate" -> resource.activate(key, body);
                    case "stop" -> resource.deactivate(key, session);
                    case "send/prepare" -> resource.prepareSend(key, session, body);
                    case "send/commit" -> resource.commitSend(key, session, body);
                    case "send/cancel" -> resource.cancelSend(key, session, body);
                    case "send/reconcile" -> resource.reconcileSend(key, session, body);
                    default -> throw new NotFoundException();
                };
            }
        };
    }

    Adapter pirate() {
        var resource = new CrossChainPirateChainResource(); resource.request = request;
        return new Adapter() {
            public Set<String> reads() { return Set.of("session", "send-capabilities", "send/status"); }
            public Set<String> writes() { return Set.of("session", "activate", "start", "stop", "status", "address", "balance", "transactions", "send", "send/readiness", "send/lookup"); }
            public Object read(String op, String key, String session, String id) {
                return switch (op) {
                    case "session" -> resource.walletSessionContract(key);
                    case "send-capabilities" -> resource.getPirateChainSendContract(key);
                    case "send/status" -> resource.getSendOperation(key, id);
                    default -> throw new NotFoundException();
                };
            }
            public Object write(String op, String key, String session, String id, InputStream body) throws IOException {
                return switch (op) {
                    case "start" -> resource.startPirateChainSingleton(key);
                    case "stop" -> resource.stopPirateChainSingleton(key);
                    case "session", "activate" -> {
                        var value = sessionBody(body);
                        if (op.equals("activate") && !"activate".equals(value.operation)) throw malformed();
                        yield resource.walletSession(key, value);
                    }
                    case "status" -> resource.getPirateChainSyncStatus(key, true, entropy(body));
                    case "address" -> resource.getPirateChainWalletAddress(key, entropy(body));
                    case "balance" -> resource.getPirateChainWalletBalance(key, verified(), entropy(body));
                    case "transactions" -> resource.getPirateChainWalletTransactions(key, entropy(body));
                    case "send/readiness" -> resource.getSendReadiness(key, entropy(body));
                    case "send/lookup" -> resource.lookupSendOperation(key, id, entropy(body));
                    // Use the existing strict money-body reader, never generic object coercion.
                    case "send" -> resource.sendPirateChain(key, new PirateChainSendRequestReader().readFrom(
                            PirateChainSendRequest.class, PirateChainSendRequest.class, new java.lang.annotation.Annotation[0],
                            MediaType.APPLICATION_JSON_TYPE, new MultivaluedHashMap<>(), body));
                    default -> throw new NotFoundException();
                };
            }
        };
    }

    private static BadRequestException malformed() { return new BadRequestException("Invalid wallet request."); }
    private static byte[] bounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(16 * 1024 + 1);
        if (bytes.length > 16 * 1024) { java.util.Arrays.fill(bytes, (byte) 0); throw malformed(); }
        return bytes;
    }
    private static String entropy(InputStream input) throws IOException {
        byte[] bytes = bounded(input);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) { throw malformed(); }
        finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }
    private static CrossChainPirateChainResource.WalletSessionRequest sessionBody(InputStream input) throws IOException {
        byte[] bytes = bounded(input);
        try {
            var node = JSON.readTree(bytes);
            if (node == null || !node.isObject() || node.size() > 3) throw malformed();
            var value = new CrossChainPirateChainResource.WalletSessionRequest();
            for (var fields = node.fields(); fields.hasNext();) {
                var field = fields.next(); if (!field.getValue().isTextual()) throw malformed();
                switch (field.getKey()) {
                    case "entropy58" -> value.entropy58 = field.getValue().asText();
                    case "operation" -> value.operation = field.getValue().asText();
                    case "expectedRevision" -> value.expectedRevision = field.getValue().asText();
                    default -> throw malformed();
                }
            }
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw malformed(); }
        finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }
}
