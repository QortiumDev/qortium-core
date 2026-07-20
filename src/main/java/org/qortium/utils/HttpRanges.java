package org.qortium.utils;

/**
 * Parsing for the HTTP {@code Range} request header.
 *
 * <p>Only a single byte range is supported, which is what media elements and resumable
 * downloads actually send. Multipart ranges are rejected rather than silently served as a
 * full body, so a caller can answer them with 416 instead of misleading the client into
 * thinking it received partial content.
 *
 * <p>Shared by the raw {@code /arbitrary} download path and the QDN {@code /render} path so
 * both expose one seeking contract.
 */
public class HttpRanges {

	private HttpRanges() {
	}

	/**
	 * Parses a single byte range against a known file size.
	 *
	 * @param rangeHeader the raw {@code Range} header value, or null/blank if absent
	 * @param fileSize    the total size of the underlying file, in bytes
	 * @return {@code { rangeStart, rangeEnd }} inclusive, clamped to the file, or null if no
	 *         range was requested and the full body should be sent
	 * @throws InvalidHttpRangeException if a range was requested but cannot be satisfied
	 */
	public static long[] parse(String rangeHeader, long fileSize) throws InvalidHttpRangeException {
		if (rangeHeader == null || rangeHeader.isBlank()) {
			return null;
		}

		String range = rangeHeader.trim();
		if (!range.regionMatches(true, 0, "bytes=", 0, "bytes=".length())) {
			throw new InvalidHttpRangeException("Range unit must be bytes");
		}

		String rangeValue = range.substring("bytes=".length()).trim();
		if (rangeValue.isEmpty() || rangeValue.contains(",")) {
			throw new InvalidHttpRangeException("Only a single byte range is supported");
		}

		int separatorIndex = rangeValue.indexOf('-');
		if (separatorIndex < 0 || separatorIndex != rangeValue.lastIndexOf('-')) {
			throw new InvalidHttpRangeException("Byte range must use start-end syntax");
		}

		String startValue = rangeValue.substring(0, separatorIndex).trim();
		String endValue = rangeValue.substring(separatorIndex + 1).trim();
		if (startValue.isEmpty() && endValue.isEmpty()) {
			throw new InvalidHttpRangeException("Byte range is empty");
		}

		if (fileSize <= 0) {
			throw new InvalidHttpRangeException("Requested range is unsatisfiable for an empty file");
		}

		long rangeStart;
		long rangeEnd;
		if (startValue.isEmpty()) {
			long suffixLength = parseUnsignedLongRangeValue(endValue);
			if (suffixLength <= 0) {
				throw new InvalidHttpRangeException("Suffix range length must be greater than zero");
			}

			rangeStart = Math.max(fileSize - suffixLength, 0);
			rangeEnd = fileSize - 1;
		} else {
			rangeStart = parseUnsignedLongRangeValue(startValue);
			if (rangeStart >= fileSize) {
				throw new InvalidHttpRangeException("Requested range starts after the end of the file");
			}

			rangeEnd = endValue.isEmpty() ? fileSize - 1 : parseUnsignedLongRangeValue(endValue);
			if (rangeEnd < rangeStart) {
				throw new InvalidHttpRangeException("Requested range ends before it starts");
			}

			rangeEnd = Math.min(rangeEnd, fileSize - 1);
		}

		return new long[] { rangeStart, rangeEnd };
	}

	private static long parseUnsignedLongRangeValue(String value) throws InvalidHttpRangeException {
		if (value == null || value.isBlank()) {
			throw new InvalidHttpRangeException("Byte range value is empty");
		}

		for (int i = 0; i < value.length(); ++i) {
			char c = value.charAt(i);
			if (c < '0' || c > '9') {
				throw new InvalidHttpRangeException("Byte range value must contain digits only");
			}
		}

		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			throw new InvalidHttpRangeException("Byte range value is too large");
		}
	}

	public static class InvalidHttpRangeException extends Exception {

		public InvalidHttpRangeException(String message) {
			super(message);
		}

	}

}
