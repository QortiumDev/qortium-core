package org.qortium.network;

import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import org.qortium.settings.Settings;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.net.*;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Convenience class for encapsulating/parsing/rendering/converting peer addresses
 * including late-stage resolving before actual use by a socket.
 */
// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class PeerAddress {

	public enum Kind {
		IP,
		I2P
	}

	private static final Pattern I2P_B32_HOST = Pattern.compile("[a-z2-7]{52}\\.b32\\.i2p", Pattern.CASE_INSENSITIVE);
	private static final String I2P_B32_SUFFIX = ".b32.i2p";

	// Properties
	private String host;
	private int port;
	private Kind kind = Kind.IP;

	private PeerAddress(String host, int port) {
		this(host, port, Kind.IP);
	}

	private PeerAddress(String host, int port, Kind kind) {
		this.kind = kind;
		this.host = normalizeHost(host, kind);
		this.port = kind == Kind.I2P ? 0 : port;
	}

	public PeerAddress(String uri) {
		if (uri == null || uri.trim().isEmpty()) {
			throw new IllegalArgumentException("Peer URI cannot be null or empty.");
		}

		String trimmedUri = uri.trim();

		// Split the URI string at the first colon, resulting in at most two parts.
		// This is generally safe for host:port separation.
		String[] parts = trimmedUri.split(":", 2);
		if (parts.length > 2) {
			throw new IllegalArgumentException("Peer URI is not formated correctly");
		}

		if (parts.length == 2) {
			// Format is "host:port"
			this.host = parts[0].trim();
			this.kind = kindForHost(this.host);
			this.host = normalizeHost(this.host, this.kind);

			try {
				// Attempt to parse the port part as an integer
				int parsedPort = Integer.parseInt(parts[1].trim());
				this.port = this.kind == Kind.I2P ? 0 : parsedPort;
			} catch (NumberFormatException e) {
				// Throw an exception if the port number is malformed (e.g., "host:abc")
				throw new IllegalArgumentException("Invalid port number in Peer URI: " + trimmedUri);
			}
		} else {
			// Format is "host" (no colon found)
			this.kind = kindForHost(trimmedUri);
			this.host = normalizeHost(trimmedUri, this.kind);
			this.port = 0; // Indicate that the port is missing/not specified
		}
	}

	// Constructors

	// For JAXB
	protected PeerAddress() {
	}

	/** Constructs new PeerAddress using remote address from passed connected socket. */
	public static PeerAddress fromSocket(Socket socket) {
		InetSocketAddress socketAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
		InetAddress address = socketAddress.getAddress();

		String host = InetAddresses.toAddrString(address);

		// Make sure we encapsulate IPv6 addresses in brackets
		if (address instanceof Inet6Address)
			host = "[" + host + "]";

		return new PeerAddress(host, socketAddress.getPort());
	}

	/**
	 * Constructs new PeerAddress using hostname or literal IP address and optional port.<br>
	 * Literal IPv6 addresses must be enclosed within square brackets.
	 * <p>
	 * Examples:
	 * <ul>
	 * <li>peer.example.com
	 * <li>peer.example.com:9084
	 * <li>192.0.2.1
	 * <li>192.0.2.1:9084
	 * <li>[2001:db8::1]
	 * <li>[2001:db8::1]:9084
	 * </ul>
	 * <p>
	 * Not allowed:
	 * <ul>
	 * <li>2001:db8::1
	 * <li>2001:db8::1:9084
	 * </ul>
	 */
	public static PeerAddress fromString(String addressString) throws IllegalArgumentException {
		if (addressString == null || addressString.trim().isEmpty())
			throw new IllegalArgumentException("Empty peer address");

		addressString = addressString.trim();
		boolean isBracketed = addressString.startsWith("[");

		// Attempt to parse string into host and port
		HostAndPort hostAndPort = HostAndPort.fromString(addressString).requireBracketsForIPv6();

		String host = hostAndPort.getHost();
		if (host.isEmpty())
			throw new IllegalArgumentException("Empty host part");

		Kind kind = kindForHost(host);

		if (kind == Kind.I2P)
			return new PeerAddress(host, 0, Kind.I2P);

		int port = hostAndPort.hasPort() ? hostAndPort.getPort() : Settings.getInstance().getDefaultListenPort();

		// Validate IP literals by attempting to convert to InetAddress, without DNS lookups
		if (host.contains(":") || host.matches("[0-9.]+"))
			InetAddresses.forString(host);

		// If we've reached this far then we have a valid address

		// Make sure we encapsulate IPv6 addresses in brackets
		if (isBracketed)
			host = "[" + host + "]";

		return new PeerAddress(host, port, Kind.IP);
	}

	// Getters

	/** Returns hostname or literal IP address, bracketed if IPv6 */
	public String getHost() {
		return this.host;
	}

	public int getPort() {
		return this.port;
	}

	public Kind getKind() {
		return this.kind != null ? this.kind : Kind.IP;
	}

	public boolean isI2P() {
		return this.getKind() == Kind.I2P;
	}

	// Conversions

	/** Returns InetSocketAddress for use with Socket.connect(), or throws UnknownHostException if address could not be resolved by DNS lookup. */
	public InetSocketAddress toSocketAddress() throws UnknownHostException {
		if (this.isI2P())
			throw new UnknownHostException("I2P peer addresses cannot be resolved via DNS");

		// Attempt to construct new InetSocketAddress with DNS lookups.
		// There's no control here over whether IPv6 or IPv4 will be used.
		InetSocketAddress socketAddress = new InetSocketAddress(this.host, this.port);

		// If we couldn't resolve then return null
		if (socketAddress.isUnresolved())
			throw new UnknownHostException();

		return socketAddress;
	}

	@Override
	public String toString() {
		return this.host + ":" + this.port;
	}

	// Utilities

	/** Returns true if other PeerAddress has same port and same case-insensitive host part, without DNS lookups */
	public boolean equals(PeerAddress other) {
		if (other == null)
			return false;

		if (this.getKind() != other.getKind())
			return false;

		// Ports must match
		if (this.port != other.port)
			return false;

		// Compare host parts but without DNS lookups
		return this.host.equalsIgnoreCase(other.host);
	}

	private static Kind kindForHost(String host) {
		if (isI2PHost(host))
			return Kind.I2P;
		if (host != null && host.toLowerCase(Locale.ROOT).contains(I2P_B32_SUFFIX))
			throw new IllegalArgumentException("Invalid I2P b32 host");
		return Kind.IP;
	}

	private static boolean isI2PHost(String host) {
		return host != null && I2P_B32_HOST.matcher(host).matches();
	}

	private static String normalizeHost(String host, Kind kind) {
		return kind == Kind.I2P ? host.toLowerCase(Locale.ROOT) : host;
	}

}
