package org.qortium.chat.crypto;

import org.qortium.crypto.Crypto;
import org.qortium.transform.TransformationException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Authenticated plaintext container carried inside a QENC v2 private chat attachment.
 * Filename, media type, content length, content digest, and file bytes are encrypted together.
 */
public final class PrivateChatAttachmentPayload {

	public static final byte[] MAGIC = { 'Q', 'A', 'T', 'T' };
	public static final byte VERSION_1 = 0x01;
	public static final int FIXED_LENGTH = 46;
	public static final int MAX_FILENAME_BYTES = 255;
	public static final int MAX_MEDIA_TYPE_BYTES = 255;

	private final String filename;
	private final String mediaType;
	private final byte[] data;

	public PrivateChatAttachmentPayload(String filename, String mediaType, byte[] data) {
		byte[] filenameBytes = validateFilename(filename);
		byte[] mediaTypeBytes = validateMediaType(mediaType);
		validateData(data);

		this.filename = new String(filenameBytes, StandardCharsets.UTF_8);
		this.mediaType = new String(mediaTypeBytes, StandardCharsets.UTF_8);
		this.data = data.clone();
	}

	public String getFilename() {
		return this.filename;
	}

	public String getMediaType() {
		return this.mediaType;
	}

	public byte[] getData() {
		return this.data.clone();
	}

	public byte[] toBytes() {
		byte[] filenameBytes = this.filename.getBytes(StandardCharsets.UTF_8);
		byte[] mediaTypeBytes = this.mediaType.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(FIXED_LENGTH
				+ filenameBytes.length + mediaTypeBytes.length + this.data.length);
		writeBytes(bytes, MAGIC);
		bytes.write(VERSION_1);
		bytes.write(0); // flags
		writeUnsignedShort(bytes, filenameBytes.length);
		writeUnsignedShort(bytes, mediaTypeBytes.length);
		writeInt(bytes, this.data.length);
		writeBytes(bytes, Crypto.digest(this.data));
		writeBytes(bytes, filenameBytes);
		writeBytes(bytes, mediaTypeBytes);
		writeBytes(bytes, this.data);
		return bytes.toByteArray();
	}

	public static PrivateChatAttachmentPayload fromBytes(byte[] bytes) throws TransformationException {
		if (bytes == null || bytes.length < FIXED_LENGTH)
			throw new TransformationException("Private chat attachment payload is too short");

		for (int index = 0; index < MAGIC.length; ++index)
			if (bytes[index] != MAGIC[index])
				throw new TransformationException("Private chat attachment payload has invalid magic");

		if (bytes[4] != VERSION_1 || bytes[5] != 0)
			throw new TransformationException("Private chat attachment payload has unsupported version or flags");

		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
		buffer.position(6);
		int filenameLength = Short.toUnsignedInt(buffer.getShort());
		int mediaTypeLength = Short.toUnsignedInt(buffer.getShort());
		long dataLength = Integer.toUnsignedLong(buffer.getInt());
		byte[] expectedDigest = new byte[32];
		buffer.get(expectedDigest);

		if (filenameLength < 1 || filenameLength > MAX_FILENAME_BYTES
				|| mediaTypeLength > MAX_MEDIA_TYPE_BYTES)
			throw new TransformationException("Private chat attachment metadata length is invalid");

		long expectedLength = (long) FIXED_LENGTH + filenameLength + mediaTypeLength + dataLength;
		if (dataLength < 1 || expectedLength != bytes.length)
			throw new TransformationException("Private chat attachment data length is invalid");

		byte[] filenameBytes = new byte[filenameLength];
		byte[] mediaTypeBytes = new byte[mediaTypeLength];
		buffer.get(filenameBytes);
		buffer.get(mediaTypeBytes);
		byte[] data = new byte[(int) dataLength];
		buffer.get(data);

		String filename = decodeUtf8(filenameBytes, "filename");
		String mediaType = decodeUtf8(mediaTypeBytes, "media type");
		try {
			validateFilename(filename);
			validateMediaType(mediaType);
		} catch (IllegalArgumentException e) {
			throw new TransformationException(e.getMessage());
		}

		if (!Arrays.equals(expectedDigest, Crypto.digest(data)))
			throw new TransformationException("Private chat attachment data digest does not match");

		return new PrivateChatAttachmentPayload(filename, mediaType, data);
	}

	private static byte[] validateFilename(String filename) {
		if (filename == null || filename.isEmpty() || filename.equals(".") || filename.equals(".."))
			throw new IllegalArgumentException("Private chat attachment filename is invalid");
		if (filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0 || containsControl(filename))
			throw new IllegalArgumentException("Private chat attachment filename is invalid");

		byte[] bytes = filename.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_FILENAME_BYTES)
			throw new IllegalArgumentException("Private chat attachment filename is too long");
		return bytes;
	}

	private static byte[] validateMediaType(String mediaType) {
		String value = mediaType == null ? "" : mediaType;
		if (containsControl(value))
			throw new IllegalArgumentException("Private chat attachment media type is invalid");

		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_MEDIA_TYPE_BYTES)
			throw new IllegalArgumentException("Private chat attachment media type is too long");
		return bytes;
	}

	private static void validateData(byte[] data) {
		if (data == null || data.length == 0)
			throw new IllegalArgumentException("Private chat attachment data is missing");
	}

	private static boolean containsControl(String value) {
		return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint));
	}

	private static String decodeUtf8(byte[] bytes, String field) throws TransformationException {
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw new TransformationException("Private chat attachment " + field + " is not valid UTF-8");
		}
	}

	private static void writeUnsignedShort(ByteArrayOutputStream bytes, int value) {
		bytes.write((value >>> 8) & 0xff);
		bytes.write(value & 0xff);
	}

	private static void writeInt(ByteArrayOutputStream bytes, int value) {
		bytes.write((value >>> 24) & 0xff);
		bytes.write((value >>> 16) & 0xff);
		bytes.write((value >>> 8) & 0xff);
		bytes.write(value & 0xff);
	}

	private static void writeBytes(ByteArrayOutputStream bytes, byte[] value) {
		bytes.write(value, 0, value.length);
	}
}
