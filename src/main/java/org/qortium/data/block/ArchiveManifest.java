package org.qortium.data.block;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The canonical list of block-archive chunks a node can serve: for each chunk, its block-height
 * range and the SHA-256 of the chunk (.dat) file. Because archive chunks are byte-identical across
 * nodes that archived the same blocks, this manifest is reproducible, so a downloaded chunk can be
 * verified against a trusted (e.g. release-pinned) manifest hash before use.
 */
// All properties to be converted to JSON via JAX-RS
@XmlAccessorType(XmlAccessType.FIELD)
public class ArchiveManifest {

	private int archiveVersion;
	private List<ArchiveChunkData> chunks;

	// For JAX-RS / JAXB
	protected ArchiveManifest() {
		this.chunks = new ArrayList<>();
	}

	public ArchiveManifest(int archiveVersion, List<ArchiveChunkData> chunks) {
		this.archiveVersion = archiveVersion;
		this.chunks = chunks;
	}

	public int getArchiveVersion() {
		return this.archiveVersion;
	}

	public List<ArchiveChunkData> getChunks() {
		return this.chunks;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ArchiveManifest that = (ArchiveManifest) o;
		return archiveVersion == that.archiveVersion && Objects.equals(chunks, that.chunks);
	}

	@Override
	public int hashCode() {
		return Objects.hash(archiveVersion, chunks);
	}
}
