package org.qortium.test;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

public class TestHygieneTests {

	@Test
	public void testNoIgnoredTests() throws IOException {
		String ignoredTestAnnotation = "@" + "Ignore";
		Path testSourceRoot = Paths.get("src", "test", "java");
		List<String> ignoredTests = new ArrayList<>();

		try (Stream<Path> sourceFiles = Files.walk(testSourceRoot)) {
			sourceFiles
					.filter(path -> path.toString().endsWith(".java"))
					.forEach(path -> collectIgnoredTestAnnotations(path, ignoredTestAnnotation, ignoredTests));
		}

		assertTrue("Remove " + ignoredTestAnnotation + " and use deterministic coverage or an explicit Assume-gated opt-in property instead: " + ignoredTests,
				ignoredTests.isEmpty());
	}

	private void collectIgnoredTestAnnotations(Path path, String ignoredTestAnnotation, List<String> ignoredTests) {
		try {
			List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

			for (int i = 0; i < lines.size(); ++i) {
				if (lines.get(i).contains(ignoredTestAnnotation))
					ignoredTests.add(path + ":" + (i + 1));
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unable to scan test source: " + path, e);
		}
	}
}
