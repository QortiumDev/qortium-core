package org.qortium.utils;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;

public abstract class ExecutorDumper {

	private static final String OUR_CLASS_NAME = ExecutorDumper.class.getName();

	public static void dump(ExecutorService executor, int checkDepth, Class<?> skipClass) {
		if (executor instanceof ThreadPoolExecutor)
			dumpThreadPoolExecutor((ThreadPoolExecutor) executor, checkDepth, skipClass);

		return;
	}

	private static void dumpThreadPoolExecutor(ThreadPoolExecutor executor, int checkDepth, Class<?> skipClass) {
		try {
			Field mainLockField = executor.getClass().getDeclaredField("mainLock");
			mainLockField.setAccessible(true);

			Field workersField = executor.getClass().getDeclaredField("workers");
			workersField.setAccessible(true);

			Class<?>[] declaredClasses = executor.getClass().getDeclaredClasses();

			Class<?> workerClass = null;
			for (int i = 0; i < declaredClasses.length; ++i)
				if (declaredClasses[i].getSimpleName().equals("Worker")) {
					workerClass = declaredClasses[i];
					break;
				}

			if (workerClass == null)
				return;

			Field workerThreadField = workerClass.getDeclaredField("thread");
			workerThreadField.setAccessible(true);

			String skipClassName = skipClass.getName();

			ReentrantLock mainLock = (ReentrantLock) mainLockField.get(executor);
			mainLock.lock();

			try {
				@SuppressWarnings("unchecked")
				HashSet<Object> workers = (HashSet<Object>) workersField.get(executor);

				WORKER_LOOP:
				for (Object workerObj : workers) {
					Thread thread = (Thread) workerThreadField.get(workerObj);

					StackTraceElement[] stackTrace = thread.getStackTrace();
					if (stackTrace.length == 0)
						continue;

					for (int d = 0; d < checkDepth; ++d) {
						String stackClassName = stackTrace[d].getClassName();
						if (stackClassName.equals(skipClassName) || stackClassName.equals(OUR_CLASS_NAME))
							continue WORKER_LOOP;
					}

					System.out.println(String.format("[%d] %s:", thread.getId(), thread.getName()));

					for (int d = 0; d < stackTrace.length; ++d)
						System.out.println(String.format("\t\t%s.%s at %s:%d",
							stackTrace[d].getClassName(), stackTrace[d].getMethodName(),
							stackTrace[d].getFileName(), stackTrace[d].getLineNumber()));
				}
			} finally {
				mainLock.unlock();
			}
		} catch (Exception e) {
			//
		}
	}

}
