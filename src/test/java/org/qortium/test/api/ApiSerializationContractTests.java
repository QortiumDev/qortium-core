package org.qortium.test.api;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.junit.Test;
import org.qortium.api.ApiService;
import org.qortium.data.rating.ResourceRatingData;
import org.qortium.data.transaction.RateResourceTransactionData;
import org.qortium.test.common.ApiCommon;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertTrue;

public class ApiSerializationContractTests extends ApiCommon {

	@Test
	public void testRestEntityTypesHaveMoxyContexts() {
		Set<Class<?>> responseTypes = new TreeSet<>(Comparator.comparing(Class::getName));
		Set<Class<?>> requestTypes = new TreeSet<>(Comparator.comparing(Class::getName));

		for (Class<?> resourceClass : ApiService.getInstance().getResources()) {
			if (!resourceClass.isAnnotationPresent(Path.class))
				continue;

			for (Method method : resourceClass.getMethods()) {
				if (!isEndpoint(method))
					continue;

				collectClasses(method.getGenericReturnType(), responseTypes);

				for (Parameter parameter : method.getParameters())
					if (!isInjectedParameter(parameter))
						collectClasses(parameter.getParameterizedType(), requestTypes);
			}
		}

		assertTrue("REST response discovery must include ResourceRatingData",
				responseTypes.contains(ResourceRatingData.class));
		assertTrue("REST request discovery must include RateResourceTransactionData",
				requestTypes.contains(RateResourceTransactionData.class));

		Set<Class<?>> entityTypes = new LinkedHashSet<>(responseTypes);
		entityTypes.addAll(requestTypes);

		List<String> failures = new ArrayList<>();
		for (Class<?> entityType : entityTypes) {
			if (!requiresMoxyContext(entityType))
				continue;

			try {
				JAXBContextFactory.createContext(new Class[] {entityType}, null);
			} catch (Exception | LinkageError e) {
				Throwable rootCause = getRootCause(e);
				failures.add(entityType.getName() + ": " + rootCause.getMessage());
			}
		}

		assertTrue("REST entity types without usable MOXy contexts:\n" + String.join("\n", failures),
				failures.isEmpty());
	}

	private static boolean isEndpoint(Method method) {
		return method.isAnnotationPresent(GET.class)
				|| method.isAnnotationPresent(POST.class)
				|| method.isAnnotationPresent(PUT.class)
				|| method.isAnnotationPresent(PATCH.class)
				|| method.isAnnotationPresent(DELETE.class);
	}

	private static boolean isInjectedParameter(Parameter parameter) {
		for (Annotation annotation : parameter.getAnnotations())
			if (annotation.annotationType().getName().startsWith("javax.ws.rs."))
				return true;

		return false;
	}

	private static void collectClasses(Type type, Set<Class<?>> classes) {
		if (type instanceof Class<?>) {
			Class<?> clazz = (Class<?>) type;
			if (clazz.isArray())
				collectClasses(clazz.getComponentType(), classes);
			else
				classes.add(clazz);
			return;
		}

		if (type instanceof ParameterizedType) {
			ParameterizedType parameterizedType = (ParameterizedType) type;
			collectClasses(parameterizedType.getRawType(), classes);
			for (Type argument : parameterizedType.getActualTypeArguments())
				collectClasses(argument, classes);
			return;
		}

		if (type instanceof GenericArrayType) {
			collectClasses(((GenericArrayType) type).getGenericComponentType(), classes);
			return;
		}

		if (type instanceof WildcardType) {
			for (Type bound : ((WildcardType) type).getUpperBounds())
				collectClasses(bound, classes);
			return;
		}

		if (type instanceof TypeVariable<?>) {
			for (Type bound : ((TypeVariable<?>) type).getBounds())
				collectClasses(bound, classes);
		}
	}

	private static boolean requiresMoxyContext(Class<?> clazz) {
		return clazz.getName().startsWith("org.qortium.")
				&& !clazz.isEnum()
				&& !clazz.isInterface()
				&& !Modifier.isAbstract(clazz.getModifiers());
	}

	private static Throwable getRootCause(Throwable throwable) {
		Throwable rootCause = throwable;
		while (rootCause.getCause() != null && rootCause.getCause() != rootCause)
			rootCause = rootCause.getCause();

		return rootCause;
	}
}
