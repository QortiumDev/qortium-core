package org.qortium.api.resource;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link ArbitraryResource#resolveDefaultFilepath}, the no-filepath default-file
 * resolution used when serving a QDN resource: single file, declared entryPoint, or none.
 */
public class ArbitraryDefaultFilepathTests {

	private static Path tempDirWithFiles(String... fileNames) throws IOException {
		Path dir = Files.createTempDirectory("qdn-entrypoint-test");
		dir.toFile().deleteOnExit();
		for (String fileName : fileNames) {
			Path file = dir.resolve(fileName);
			Files.write(file, new byte[] { 1 });
			file.toFile().deleteOnExit();
		}
		return dir;
	}

	@Test
	public void singleFileResolvesToThatFile() throws IOException {
		Path dir = tempDirWithFiles("only.mp4");
		// A single-file resource serves its one file regardless of any entryPoint.
		assertEquals("only.mp4", ArbitraryResource.resolveDefaultFilepath(dir, new String[] { "only.mp4" }, null));
		assertEquals("only.mp4", ArbitraryResource.resolveDefaultFilepath(dir, new String[] { "only.mp4" }, "ignored.mp4"));
	}

	@Test
	public void declaredEntryPointResolvesWhenPresent() throws IOException {
		Path dir = tempDirWithFiles("movie.mp4", "movie.srt");
		assertEquals("movie.mp4",
				ArbitraryResource.resolveDefaultFilepath(dir, new String[] { "movie.mp4", "movie.srt" }, "movie.mp4"));
	}

	@Test
	public void multiFileWithoutEntryPointResolvesToNull() throws IOException {
		Path dir = tempDirWithFiles("movie.mp4", "movie.srt");
		assertNull(ArbitraryResource.resolveDefaultFilepath(dir, new String[] { "movie.mp4", "movie.srt" }, null));
	}

	@Test
	public void missingEntryPointFileResolvesToNull() throws IOException {
		Path dir = tempDirWithFiles("movie.mp4", "movie.srt");
		assertNull(ArbitraryResource.resolveDefaultFilepath(dir, new String[] { "movie.mp4", "movie.srt" }, "nope.mp4"));
	}

	@Test
	public void unsafeEntryPointResolvesToNullWithoutThrowing() throws IOException {
		Path dir = tempDirWithFiles("movie.mp4", "movie.srt");
		// A traversal/escaping entryPoint must be treated as unusable, not abort with an exception.
		assertNull(ArbitraryResource.resolveDefaultFilepath(dir, new String[] { "movie.mp4", "movie.srt" }, "../secret"));
	}

	@Test
	public void emptyResourceResolvesToNull() throws IOException {
		Path dir = tempDirWithFiles();
		assertNull(ArbitraryResource.resolveDefaultFilepath(dir, new String[0], null));
		assertNull(ArbitraryResource.resolveDefaultFilepath(dir, null, null));
	}
}
