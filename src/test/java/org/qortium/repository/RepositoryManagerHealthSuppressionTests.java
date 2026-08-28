package org.qortium.repository;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RepositoryManagerHealthSuppressionTests {

	@Test
	public void testPlannedMaintenanceSuppressesHealthRecoveryOnlyInsideOperation() throws Exception {
		AtomicBoolean suppressionObserved = new AtomicBoolean();
		Repository repository = repositoryProxy("performPeriodicMaintenance", suppressionObserved);

		RepositoryManager.performPeriodicMaintenance(repository, 1000L);

		assertTrue(suppressionObserved.get());
		assertFalse(RepositoryManager.isHealthCheckSuppressed());
		assertTrue(RepositoryManager.getHealthCheckSuppressedSince() == 0L);
	}

	@Test
	public void testPlannedBackupSuppressesHealthRecoveryOnlyInsideOperation() throws Exception {
		AtomicBoolean suppressionObserved = new AtomicBoolean();
		Repository repository = repositoryProxy("backup", suppressionObserved);

		RepositoryManager.backup(repository, true, "backup", 1000L);

		assertTrue(suppressionObserved.get());
		assertFalse(RepositoryManager.isHealthCheckSuppressed());
		assertTrue(RepositoryManager.getHealthCheckSuppressedSince() == 0L);
	}

	private static Repository repositoryProxy(String observedMethod, AtomicBoolean suppressionObserved) {
		return (Repository) Proxy.newProxyInstance(
				Repository.class.getClassLoader(),
				new Class<?>[]{Repository.class},
				(proxy, method, args) -> {
					if (method.getName().equals(observedMethod)) {
						suppressionObserved.set(RepositoryManager.isHealthCheckSuppressed());
						return null;
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}
}
