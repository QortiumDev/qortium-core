package org.qortium.api.resource;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import org.qortium.api.ApiError;
import org.qortium.api.ApiException;
import org.qortium.api.model.crosschain.PirateChainSendRequest;

import javax.ws.rs.Consumes;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.HashSet;
import java.util.Set;

/**
 * Strict JSON reader for {@link PirateChainSendRequest}, replacing the generic JAXB/MOXy binding
 * for this one money-moving body.
 * <p>
 * The generic binding coerces: {@code "arrrAmount":[1,2]} binds {@code "2"}, {@code 1e0} binds
 * {@code "1"}, {@code "memo":["first","last"]} binds {@code "last"} and {@code "feePerByte":[]}
 * binds null, so malformed input could select a monetary value or drop memo content before the
 * resource's validation ever saw it. This reader walks the JSON tokens itself: every field must be
 * a scalar, {@code arrrAmount} and {@code feePerByte} may be a JSON string or a JSON number (whose
 * ORIGINAL lexical text is kept, so {@code 1e0} still reaches - and fails - the amount parser),
 * arrays, objects, booleans, duplicate fields, unknown fields and trailing content are rejected.
 * Rejections are HTTP 400 {@link ApiError#INVALID_DATA} with a stable {@code MALFORMED_BODY} token
 * and never echo the body. An empty body reads as null so the resource reports MISSING_BODY.
 */
@Provider
@Consumes(MediaType.APPLICATION_JSON)
public class PirateChainSendRequestReader implements MessageBodyReader<PirateChainSendRequest> {

	private static final JsonFactory JSON_FACTORY = new JsonFactory();

	/** Generous upper bound for a body that legitimately holds at most a few hundred bytes. */
	static final int MAX_BODY_BYTES = 16 * 1024;

	static final String REJECTION_TOKEN = "MALFORMED_BODY";

	@Override
	public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
		return PirateChainSendRequest.class == type;
	}

	@Override
	public PirateChainSendRequest readFrom(Class<PirateChainSendRequest> type, Type genericType,
			Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders,
			InputStream entityStream) throws IOException, WebApplicationException {
		byte[] body = readBounded(entityStream);
		try {
			return parse(StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(body)).toString());
		} catch (CharacterCodingException e) {
			throw reject("invalid UTF-8");
		}
	}

	private static byte[] readBounded(InputStream entityStream) throws IOException {
		byte[] body = entityStream.readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES)
			throw reject("body exceeds " + MAX_BODY_BYTES + " bytes");
		return body;
	}

	/**
	 * Parses one request body. Package-private so tests can drive the exact reader logic with
	 * raw JSON text.
	 *
	 * @return null for an empty/blank body
	 * @throws ApiException 400 INVALID_DATA "MALFORMED_BODY: ..." for anything that is not a flat
	 *         JSON object of scalar fields
	 */
	static PirateChainSendRequest parse(String text) {
		if (text == null || text.isBlank())
			return null;

		PirateChainSendRequest request = new PirateChainSendRequest();
		Set<String> seen = new HashSet<>();
		try (JsonParser parser = JSON_FACTORY.createParser(text)) {
			if (parser.nextToken() != JsonToken.START_OBJECT)
				throw reject("body must be a JSON object");

			for (JsonToken token = parser.nextToken(); token != JsonToken.END_OBJECT; token = parser.nextToken()) {
				if (token != JsonToken.FIELD_NAME)
					throw reject("unexpected token");
				String field = parser.currentName();
				if (!seen.add(field))
					throw reject("duplicate field");
				JsonToken valueToken = parser.nextToken();

				switch (field) {
					case "entropy58" -> request.entropy58 = stringOnly(parser, valueToken);
					case "receivingAddress" -> request.receivingAddress = stringOnly(parser, valueToken);
					case "memo" -> request.memo = stringOnly(parser, valueToken);
					case "idempotencyKey" -> request.idempotencyKey = stringOnly(parser, valueToken);
					case "arrrAmount" -> request.arrrAmount = stringOrNumber(parser, valueToken);
					case "feePerByte" -> request.feePerByte = stringOrNumber(parser, valueToken);
					default -> throw reject("unknown field");
				}
			}

			if (parser.nextToken() != null)
				throw reject("trailing content after the JSON object");
		} catch (JsonProcessingException e) {
			throw reject("invalid JSON");
		} catch (IOException e) {
			throw reject("unreadable body");
		}

		return request;
	}

	private static String stringOnly(JsonParser parser, JsonToken token) throws IOException {
		if (token == JsonToken.VALUE_NULL)
			return null;
		if (token == JsonToken.VALUE_STRING)
			return parser.getText();
		throw reject("field must be a JSON string");
	}

	private static String stringOrNumber(JsonParser parser, JsonToken token) throws IOException {
		if (token == JsonToken.VALUE_NULL)
			return null;
		// getText() returns the number exactly as written in the body (e.g. "1e0", "1.50"), never a
		// re-rendered value, so the amount parser judges what the client actually sent.
		if (token == JsonToken.VALUE_STRING || token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT)
			return parser.getText();
		throw reject("field must be a JSON string or number");
	}

	private static ApiException reject(String detail) {
		return new ApiException(ApiError.INVALID_DATA.getStatus(), ApiError.INVALID_DATA.getCode(),
				REJECTION_TOKEN + ": " + detail);
	}
}
