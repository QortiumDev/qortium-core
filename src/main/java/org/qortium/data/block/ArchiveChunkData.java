package org.qortium.data.block;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.Objects;

// All properties to be converted to JSON via JAX-RS
@XmlAccessorType(XmlAccessType.FIELD)
public class ArchiveChunkData {

	private int startHeight;
	private int endHeight;
	/** Lowercase hex SHA-256 of the complete archive chunk (.dat) file. */
	private String sha256;
	/** Size of the chunk file in bytes. */
	private long size;

	// For JAX-RS / JAXB
	protected ArchiveChunkData() {
	}

	public ArchiveChunkData(int startHeight, int endHeight, String sha256, long size) {
		this.startHeight = startHeight;
		this.endHeight = endHeight;
		this.sha256 = sha256;
		this.size = size;
	}

	public int getStartHeight() {
		return this.startHeight;
	}

	public int getEndHeight() {
		return this.endHeight;
	}

	public String getSha256() {
		return this.sha256;
	}

	public long getSize() {
		return this.size;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ArchiveChunkData that = (ArchiveChunkData) o;
		return startHeight == that.startHeight && endHeight == that.endHeight && size == that.size
				&& Objects.equals(sha256, that.sha256);
	}

	@Override
	public int hashCode() {
		return Objects.hash(startHeight, endHeight, sha256, size);
	}

	@Override
	public String toString() {
		return "ArchiveChunkData{startHeight=" + startHeight + ", endHeight=" + endHeight
				+ ", sha256='" + sha256 + '\'' + ", size=" + size + '}';
	}
}
