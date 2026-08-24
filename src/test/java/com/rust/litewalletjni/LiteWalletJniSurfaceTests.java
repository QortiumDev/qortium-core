package com.rust.litewalletjni;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class LiteWalletJniSurfaceTests {

	@Test
	public void testUnifiedJniDeclarationSurface() {
		Set<String> nativeMethods = java.util.Arrays.stream(LiteWalletJni.class.getDeclaredMethods())
				.filter(method -> Modifier.isNative(method.getModifiers()))
				.map(Method::getName)
				.collect(Collectors.toSet());

		assertEquals(Set.of(
				"initlogging",
				"initnew",
				"initfromseed",
				"initfromb64",
				"save",
				"execute",
				"getseedphrase",
				"getseedphrasefromentropyb64",
				"getseedphrasefromentropy",
				"checkseedphrase",
				"configurestorage",
				"invokeJson"), nativeMethods);
	}
}
