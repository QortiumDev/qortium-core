package org.qortium.test.api;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.persistence.jaxb.rs.MOXyJsonProvider;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks that every {@link TransactionData} subclass can survive the exact JSON binding the API uses.
 * <p>
 * {@code TransactionData} is annotated {@code @XmlDiscriminatorNode("type")}, so MOXy needs a matching
 * {@code @XmlDiscriminatorValue} on every subclass. A subclass missing it makes MOXy throw while binding
 * the POST body, before any resource method runs, and the endpoint returns a bare 500 with no JSON body.
 * <p>
 * These tests deliberately discover the subclasses by scanning the compiled package rather than using a
 * hard-coded list, so a newly added transaction type is covered automatically.
 */
public class TransactionDataDiscriminatorTests {

	private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];
	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;

	/** Enough bytes for Crypto.toAddress() while marshalling; contents are irrelevant here. */
	private static final byte[] DUMMY_PUBLIC_KEY = new byte[32];

	@BeforeClass
	public static void addCryptoProvider() {
		// Marshalling reaches Crypto.toAddress(), which needs BouncyCastle's RIPEMD160 just as the running node does.
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
			Security.insertProviderAt(new BouncyCastleProvider(), 0);
	}

	@Test
	public void testEveryTransactionDataSubclassDeclaresItsDiscriminatorValue() throws Exception {
		List<Class<? extends TransactionData>> subclasses = findConcreteTransactionDataSubclasses();

		List<String> problems = new ArrayList<>();

		for (Class<? extends TransactionData> subclass : subclasses) {
			// The subclass's own no-arg JAXB constructor is what sets its TransactionType,
			// so it is the authority on which discriminator value the subclass must declare.
			String expectedValue = newInstance(subclass).getType().name();

			XmlDiscriminatorValue annotation = subclass.getAnnotation(XmlDiscriminatorValue.class);
			if (annotation == null) {
				problems.add(subclass.getSimpleName() + " is missing @XmlDiscriminatorValue(\"" + expectedValue + "\")");
				continue;
			}

			if (!expectedValue.equals(annotation.value()))
				problems.add(subclass.getSimpleName() + " declares @XmlDiscriminatorValue(\"" + annotation.value()
						+ "\") but its transaction type is " + expectedValue);
		}

		assertEquals("every TransactionData subclass must declare the discriminator value matching its transaction type",
				List.of(), problems);
	}

	@Test
	public void testEveryTransactionDataSubclassRoundTripsThroughApiJsonBinding() throws Exception {
		List<Class<? extends TransactionData>> subclasses = findConcreteTransactionDataSubclasses();

		List<String> failures = new ArrayList<>();

		for (Class<? extends TransactionData> subclass : subclasses)
			try {
				roundTrip(subclass);
			} catch (Exception | LinkageError e) {
				failures.add(subclass.getSimpleName() + ": " + describe(e));
			}

		assertEquals("every TransactionData subclass must round-trip through the API's JSON binding",
				List.of(), failures);
	}

	/**
	 * Marshals then unmarshals using the same JAX-RS message body provider Jersey uses for the API's JSON
	 * bodies, with both declared types the API actually presents to it: the concrete subclass, as request
	 * bodies are declared (for example {@code NamesResource.updateName(UpdateNameTransactionData)}), and
	 * the abstract base class, as responses are declared (for example {@code TransactionsResource} methods
	 * returning {@code TransactionData}), which makes MOXy pick the subclass by discriminator value.
	 */
	private static void roundTrip(Class<? extends TransactionData> subclass) throws Exception {
		TransactionData transactionData = newInstance(subclass);
		transactionData.setCreatorPublicKey(DUMMY_PUBLIC_KEY);
		transactionData.setFee(0L);

		String expectedType = "\"type\":\"" + transactionData.getType().name() + "\"";

		MOXyJsonProvider provider = createProvider();

		String asSubclass = marshal(provider, transactionData, subclass);
		assertTrue(subclass.getSimpleName() + " marshalled without its type: " + asSubclass, asSubclass.contains(expectedType));

		String asBaseClass = marshal(provider, transactionData, TransactionData.class);
		assertTrue(subclass.getSimpleName() + " marshalled without its type: " + asBaseClass, asBaseClass.contains(expectedType));

		assertEquals(subclass, unmarshal(provider, asSubclass, subclass).getClass());

		assertEquals("MOXy resolved the wrong subclass from the \"type\" discriminator",
				subclass, unmarshal(provider, asBaseClass, TransactionData.class).getClass());
	}

	/** MOXy binding errors arrive wrapped in a bare HTTP 500, so report the whole cause chain instead. */
	private static String describe(Throwable throwable) {
		StringBuilder description = new StringBuilder();

		for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
			if (description.length() > 0)
				description.append(" <- ");

			description.append(cause.getClass().getSimpleName()).append(": ")
					.append(String.valueOf(cause.getMessage()).replaceAll("\\s+", " "));

			if (cause.getCause() == cause)
				break;
		}

		return description.toString();
	}

	private static MOXyJsonProvider createProvider() {
		MOXyJsonProvider provider = new MOXyJsonProvider();
		// Matches how the API's JSON bodies are shaped: bare objects, no JSON root element wrapper.
		provider.setIncludeRoot(false);
		return provider;
	}

	private static String marshal(MOXyJsonProvider provider, TransactionData transactionData, Class<?> declaredType)
			throws Exception {
		MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		provider.writeTo(transactionData, declaredType, declaredType, NO_ANNOTATIONS, JSON, headers, output);

		return output.toString(StandardCharsets.UTF_8);
	}

	@SuppressWarnings("unchecked")
	private static Object unmarshal(MOXyJsonProvider provider, String json, Class<?> declaredType) throws Exception {
		MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
		ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

		return provider.readFrom((Class<Object>) declaredType, declaredType, NO_ANNOTATIONS, JSON, headers, input);
	}

	private static TransactionData newInstance(Class<? extends TransactionData> subclass) throws Exception {
		Constructor<? extends TransactionData> constructor = subclass.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	/** Discovers subclasses from the compiled classes directory so future transaction types are covered too. */
	private static List<Class<? extends TransactionData>> findConcreteTransactionDataSubclasses() throws Exception {
		URL location = TransactionData.class.getProtectionDomain().getCodeSource().getLocation();
		Path classesRoot = Paths.get(location.toURI());
		assertTrue("expected compiled classes directory, found " + classesRoot, Files.isDirectory(classesRoot));

		Path packageDirectory = classesRoot.resolve(TransactionData.class.getPackage().getName().replace('.', '/'));
		assertTrue("missing package directory " + packageDirectory, Files.isDirectory(packageDirectory));

		List<Class<? extends TransactionData>> subclasses;

		try (Stream<Path> classFiles = Files.list(packageDirectory)) {
			subclasses = classFiles
					.map(path -> path.getFileName().toString())
					.filter(fileName -> fileName.endsWith(".class") && !fileName.contains("$"))
					.map(fileName -> fileName.substring(0, fileName.length() - ".class".length()))
					.map(TransactionDataDiscriminatorTests::loadClass)
					.filter(TransactionData.class::isAssignableFrom)
					.filter(clazz -> !Modifier.isAbstract(clazz.getModifiers()))
					.filter(clazz -> !TransactionData.class.equals(clazz))
					// BaseTransactionData only carries the properties shared by all transactions,
					// has no transaction type of its own and is never bound to a request body.
					.filter(clazz -> !BaseTransactionData.class.equals(clazz))
					.map(clazz -> clazz.asSubclass(TransactionData.class))
					.sorted(Comparator.comparing(Class::getSimpleName))
					.collect(Collectors.toList());
		}

		// Sanity check that discovery actually worked, rather than silently passing over an empty list.
		assertTrue("expected to discover the TransactionData subclasses, found " + subclasses.size(), subclasses.size() > 20);

		return subclasses;
	}

	private static Class<?> loadClass(String simpleName) {
		try {
			return Class.forName(TransactionData.class.getPackage().getName() + "." + simpleName);
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("unable to load " + simpleName, e);
		}
	}

}
