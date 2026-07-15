package org.qortium.api;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.qortium.settings.Settings;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Bounds anonymous remote write work admitted through the public API allowlist.
 * Requests from the node's normal API whitelist or carrying its valid API key
 * bypass these limits and retain the existing trusted/local behavior.
 */
public class PublicApiProtectionHandler extends Handler.Wrapper {

	private static final long NANOS_PER_MINUTE = Duration.ofMinutes(1).toNanos();
	private static final long QDN_BODY_OVERHEAD = 1024L * 1024L;

	enum RequestClass {
		NONE,
		BUILDER,
		PROCESS,
		QDN
	}

	private final LongSupplier nanoClock;
	private final Cache<String, RateBucket> clientBuckets = CacheBuilder.newBuilder()
			.maximumSize(100_000)
			.expireAfterAccess(10, TimeUnit.MINUTES)
			.build();
	private final AtomicInteger activeBuilders = new AtomicInteger();
	private final AtomicInteger activeProcesses = new AtomicInteger();
	private final AtomicInteger activeQdnRequests = new AtomicInteger();

	public PublicApiProtectionHandler() {
		this(System::nanoTime);
	}

	PublicApiProtectionHandler(LongSupplier nanoClock) {
		this.nanoClock = nanoClock;
	}

	@Override
	public boolean handle(Request request, Response response, Callback callback) throws Exception {
		Settings settings = Settings.getInstance();
		String remoteAddress = Request.getRemoteAddr(request);
		String passedApiKey = request.getHeaders().get(Security.API_KEY_HEADER);
		String nodeApiKey = passedApiKey == null || passedApiKey.isBlank()
				? null
				: PublicApiAccessHandler.getNodeApiKey();
		RequestClass requestClass = classify(request.getMethod(), request.getHttpURI().getPath());

		if (requestClass == RequestClass.NONE
				|| PublicApiAccessHandler.isTrustedRequest(remoteAddress, passedApiKey, nodeApiKey, settings))
			return super.handle(request, response, callback);

		long now = this.nanoClock.getAsLong();
		if (!allowRate(remoteAddress, requestClass, settings, now)) {
			response.getHeaders().put(HttpHeader.RETRY_AFTER, "1");
			Response.writeError(request, response, callback, HttpStatus.TOO_MANY_REQUESTS_429,
					"Public API request rate exceeded");
			return true;
		}

		long bodyLimit = bodyLimit(requestClass, settings);
		HttpField contentLength = request.getHeaders().getField(HttpHeader.CONTENT_LENGTH);
		if (contentLength != null && contentLength.getLongValue() > bodyLimit) {
			Response.writeError(request, response, callback, HttpStatus.PAYLOAD_TOO_LARGE_413,
					"Public API request body exceeds " + bodyLimit + " bytes");
			return true;
		}

		AtomicInteger active = activeCounter(requestClass);
		int maxConcurrent = maxConcurrent(requestClass, settings);
		if (!tryAcquire(active, maxConcurrent)) {
			response.getHeaders().put(HttpHeader.RETRY_AFTER, "1");
			Response.writeError(request, response, callback, HttpStatus.TOO_MANY_REQUESTS_429,
					"Public API request concurrency exceeded");
			return true;
		}

		AtomicBoolean released = new AtomicBoolean();
		Runnable release = () -> {
			if (released.compareAndSet(false, true))
				active.decrementAndGet();
		};
		Callback releasingCallback = new Callback() {
			@Override
			public void succeeded() {
				try {
					callback.succeeded();
				} finally {
					release.run();
				}
			}

			@Override
			public void failed(Throwable failure) {
				try {
					callback.failed(failure);
				} finally {
					release.run();
				}
			}

			@Override
			public InvocationType getInvocationType() {
				return callback.getInvocationType();
			}
		};

		try {
			boolean handled = super.handle(new LimitedRequest(request, bodyLimit), response, releasingCallback);
			if (!handled)
				release.run();
			return handled;
		} catch (Exception | Error e) {
			release.run();
			throw e;
		}
	}

	private boolean allowRate(String remoteAddress, RequestClass requestClass, Settings settings, long now) {
		String key = requestClass.name() + '\0' + (remoteAddress == null ? "unknown" : remoteAddress);
		RateBucket bucket = this.clientBuckets.getIfPresent(key);
		if (bucket == null) {
			RateBucket candidate = new RateBucket(now);
			RateBucket existing = this.clientBuckets.asMap().putIfAbsent(key, candidate);
			bucket = existing == null ? candidate : existing;
		}

		int rate = requestClass == RequestClass.PROCESS
				? settings.getPublicApiProcessRequestsPerMinute()
				: settings.getPublicApiBuilderRequestsPerMinute();
		int burst = requestClass == RequestClass.PROCESS
				? settings.getPublicApiProcessRateLimitBurst()
				: settings.getPublicApiBuilderRateLimitBurst();
		return bucket.tryAcquire(now, rate, burst);
	}

	private AtomicInteger activeCounter(RequestClass requestClass) {
		return switch (requestClass) {
			case BUILDER -> this.activeBuilders;
			case PROCESS -> this.activeProcesses;
			case QDN -> this.activeQdnRequests;
			case NONE -> throw new IllegalArgumentException("Unprotected request class");
		};
	}

	private static int maxConcurrent(RequestClass requestClass, Settings settings) {
		return switch (requestClass) {
			case BUILDER -> settings.getPublicApiBuilderMaxConcurrentRequests();
			case PROCESS -> settings.getPublicApiProcessMaxConcurrentRequests();
			case QDN -> settings.getPublicQdnApiMaxConcurrentRequests();
			case NONE -> Integer.MAX_VALUE;
		};
	}

	static long bodyLimit(RequestClass requestClass, Settings settings) {
		if (requestClass != RequestClass.QDN)
			return settings.getPublicApiWriteMaxBodySize();

		// Public QDN base64/ZIP bodies can be 4/3 larger than the decoded source.
		// Leave bounded room for encoding, encryption and archive metadata.
		long publishLimit = settings.getPublicQdnPublishMaxSize();
		if (publishLimit > (Long.MAX_VALUE - QDN_BODY_OVERHEAD - 2L) / 4L)
			return Long.MAX_VALUE;
		return (publishLimit * 4L + 2L) / 3L + QDN_BODY_OVERHEAD;
	}

	static RequestClass classify(String method, String path) {
		if (method == null || path == null)
			return RequestClass.NONE;

		String normalizedMethod = method.trim().toUpperCase(Locale.ROOT);
		if ("GET".equals(normalizedMethod) && isPublicStagedDataPath(path))
			return RequestClass.QDN;
		if (!"POST".equals(normalizedMethod))
			return RequestClass.NONE;
		if (path.startsWith("/arbitrary/public/"))
			return RequestClass.QDN;
		if ("/transactions/process".equals(path))
			return RequestClass.PROCESS;
		if ("/transactions/convert".equals(path)
				|| "/chat/public/build".equals(path)
				|| "/polls/public/create".equals(path)
				|| "/polls/public/vote".equals(path)
				|| "/polls/public/update".equals(path))
			return RequestClass.BUILDER;
		return RequestClass.NONE;
	}

	private static boolean isPublicStagedDataPath(String path) {
		String prefix = "/arbitrary/public/data/";
		return path.startsWith(prefix) && path.length() > prefix.length()
				&& path.indexOf('/', prefix.length()) < 0;
	}

	static boolean tryAcquire(AtomicInteger active, int maximum) {
		while (true) {
			int current = active.get();
			if (current >= maximum)
				return false;
			if (active.compareAndSet(current, current + 1))
				return true;
		}
	}

	static final class RateBucket {
		private double tokens;
		private long lastRefill;
		private boolean initialized;

		RateBucket(long now) {
			this.lastRefill = now;
		}

		synchronized boolean tryAcquire(long now, int requestsPerMinute, int burst) {
			long elapsed = Math.max(0L, now - this.lastRefill);
			if (!this.initialized) {
				this.tokens = burst;
				this.initialized = true;
			} else {
				this.tokens = Math.min(burst,
						this.tokens + (double) elapsed * requestsPerMinute / NANOS_PER_MINUTE);
			}

			this.lastRefill = now;
			if (this.tokens < 1.0)
				return false;
			this.tokens -= 1.0;
			return true;
		}
	}

	private static final class LimitedRequest extends Request.Wrapper {
		private final long limit;
		private long read;

		private LimitedRequest(Request request, long limit) {
			super(request);
			this.limit = limit;
		}

		@Override
		public Content.Chunk read() {
			Content.Chunk chunk = super.read();
			if (chunk == null || chunk.getFailure() != null)
				return chunk;

			ByteBuffer buffer = chunk.getByteBuffer();
			if (buffer != null && buffer.hasRemaining()) {
				this.read += buffer.remaining();
				if (this.read > this.limit) {
					HttpException.RuntimeException failure = new HttpException.RuntimeException(
							HttpStatus.PAYLOAD_TOO_LARGE_413,
							"Public API request body exceeds " + this.limit + " bytes");
					chunk.release();
					getWrapped().fail(failure);
					return null;
				}
			}
			return chunk;
		}
	}
}
