package org.qortium.repository;

import org.junit.Test;
import org.qortium.utils.Triple;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BlockArchiveReaderFilenameTests {

	@Test
	public void testAcceptsExactArchiveFilename() {
		Triple<Integer, Integer, Integer> range = BlockArchiveReader.parseArchiveFilename("2-100.dat");

		assertEquals(Integer.valueOf(2), range.getA());
		assertEquals(Integer.valueOf(100), range.getB());
		assertEquals(Integer.valueOf(98), range.getC());
	}

	@Test
	public void testRejectsTemporaryAndMalformedArchiveFilenames() {
		String[] invalidFilenames = {
				null,
				".2-100.dat.tmp",
				"2-100.dat.tmp",
				"2-100.zip",
				"2-100",
				"2.dat",
				"2-100-200.dat",
				"-2-100.dat",
				"100-2.dat",
				"2147483648-2147483649.dat"
		};

		for (String filename : invalidFilenames)
			assertNull(filename, BlockArchiveReader.parseArchiveFilename(filename));
	}
}
