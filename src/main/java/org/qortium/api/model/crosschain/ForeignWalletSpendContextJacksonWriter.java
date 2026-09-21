package org.qortium.api.model.crosschain;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * Serializes {@link ForeignWalletSpendContext} via plain Jackson instead of the API's default
 * MOXy JSON provider.
 * <p>
 * MOXy has no adapter for the {@code previousTransactions} field (a plain {@code Map<String,String>})
 * and falls back to its generic map representation, {@code {"entry":[{"key":...,"value":...}]}}
 * (or {@code {"entry":[]}} when empty), instead of a plain JSON object keyed by tx hash. Consumers
 * (e.g. Qortium Home's foreign-wallet-spend-context.ts) expect {@code {"<txhash>":"<rawhex>", ...}}.
 * <p>
 * Modelled on {@link org.qortium.api.model.ConnectedPeerJacksonWriter}: a plain, unconfigured
 * Jackson {@link ObjectMapper} reflects the same getter-derived property names JAXB/MOXy would use
 * for this class (no {@code @XmlElement} renames are present on {@link ForeignWalletSpendContext}
 * or {@link ForeignWalletSpendContextUtxo}), so every field other than the map is unaffected.
 */
@Provider
@Produces(MediaType.APPLICATION_JSON)
public class ForeignWalletSpendContextJacksonWriter implements MessageBodyWriter<ForeignWalletSpendContext> {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
		return ForeignWalletSpendContext.class.isAssignableFrom(type);
	}

	@Override
	public long getSize(ForeignWalletSpendContext spendContext, Class<?> type, Type genericType,
			Annotation[] annotations, MediaType mediaType) {
		return -1; // Size is unknown
	}

	@Override
	public void writeTo(ForeignWalletSpendContext spendContext, Class<?> type, Type genericType,
			Annotation[] annotations, MediaType mediaType,
			MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream)
			throws IOException {
		objectMapper.writer().writeValue(entityStream, spendContext);
	}
}
