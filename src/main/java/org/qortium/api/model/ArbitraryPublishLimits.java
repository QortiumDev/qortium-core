package org.qortium.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;

/**
 * Effective QDN publish size limits, so clients (e.g. Qortium Home) can
 * pre-flight a file or folder before staging/uploading anything.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryPublishLimits {

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class ServiceLimit {
		public String service;
		/** Raw (pre-compression) size limit in bytes; null means no service-specific limit. */
		public Long maxSize;

		public ServiceLimit() {
		}

		public ServiceLimit(String service, Long maxSize) {
			this.service = service;
			this.maxSize = maxSize;
		}
	}

	/** Hard cap on any single QDN payload after compression/encryption, in bytes. */
	public long maxFileSize;
	/** Cap applied to authenticated publish-builder uploads, in bytes. */
	public long publishMaxSize;
	/** Cap applied to public (keyless) publish-builder uploads, in bytes. */
	public long publicPublishMaxSize;
	/** Per-chunk cap for public chunked uploads, in bytes. */
	public long publicPublishChunkMaxSize;
	/** Concurrent public chunk-upload sessions allowed per IP and resource. */
	public int publicPublishChunkSessionLimit;
	/** Per-service raw-size limits. */
	public List<ServiceLimit> serviceLimits;

	public ArbitraryPublishLimits() {
	}
}
