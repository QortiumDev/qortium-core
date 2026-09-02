package org.qortium.crosschain;

import java.util.Objects;
import java.util.Optional;

/**
 * An ElectrumX protocol version, e.g. <code>1.4</code> or <code>1.4.2</code>.
 * <p>
 * Protocol versions are dotted component lists, not decimal numbers: parsing them as doubles makes
 * <code>1.10</code> collapse to 1.1 and <code>1.20</code> to 1.2, so a server speaking a far newer
 * protocol than Core would compare as an old one and be wrongly accepted. Every comparison here is
 * made component by component with integer semantics instead.
 *
 * @see <a href="https://electrumx.readthedocs.io/en/latest/protocol-changes.html">ElectrumX protocol changes</a>
 */
public final class ElectrumProtocolVersion implements Comparable<ElectrumProtocolVersion> {

	private static final int MAX_COMPONENTS = 3;
	private static final int MAX_COMPONENT_DIGITS = 9;

	private final int major;
	private final int minor;
	private final int patch;
	/** Whether the source string carried a patch component; a ceiling without one covers its whole x.y family. */
	private final boolean patchSpecified;

	private ElectrumProtocolVersion(int major, int minor, int patch, boolean patchSpecified) {
		this.major = major;
		this.minor = minor;
		this.patch = patch;
		this.patchSpecified = patchSpecified;
	}

	/** A two-component version, e.g. <code>of(1, 4)</code> for the whole 1.4.x family. */
	public static ElectrumProtocolVersion of(int major, int minor) {
		return new ElectrumProtocolVersion(major, minor, 0, false);
	}

	/** A three-component version, e.g. <code>of(1, 4, 2)</code> for exactly 1.4.2. */
	public static ElectrumProtocolVersion of(int major, int minor, int patch) {
		return new ElectrumProtocolVersion(major, minor, patch, true);
	}

	/**
	 * Parse <code>major.minor</code> or <code>major.minor.patch</code>.
	 *
	 * @return the parsed version, or empty when the value is missing or not a valid protocol version
	 */
	public static Optional<ElectrumProtocolVersion> parse(String value) {
		if (value == null)
			return Optional.empty();

		String[] parts = value.trim().split("\\.", -1);
		if (parts.length < 2 || parts.length > MAX_COMPONENTS)
			return Optional.empty();

		int[] components = new int[MAX_COMPONENTS];
		for (int index = 0; index < parts.length; index++) {
			String part = parts[index];
			if (part.isEmpty() || part.length() > MAX_COMPONENT_DIGITS || !part.chars().allMatch(Character::isDigit))
				return Optional.empty();

			components[index] = Integer.parseInt(part);
		}

		return Optional.of(new ElectrumProtocolVersion(components[0], components[1], components[2], parts.length == MAX_COMPONENTS));
	}

	@Override
	public int compareTo(ElectrumProtocolVersion other) {
		int comparison = Integer.compare(this.major, other.major);
		if (comparison != 0)
			return comparison;

		comparison = Integer.compare(this.minor, other.minor);
		if (comparison != 0)
			return comparison;

		return Integer.compare(this.patch, other.patch);
	}

	/**
	 * @return true when this version is at or below <code>ceiling</code>. A ceiling written without a patch
	 * component covers its whole family, so 1.4.2 is at or below a ceiling of 1.4 while 1.5, 1.10 and 2.0 are not.
	 */
	public boolean isAtOrBelow(ElectrumProtocolVersion ceiling) {
		if (ceiling.patchSpecified)
			return compareTo(ceiling) <= 0;

		int comparison = Integer.compare(this.major, ceiling.major);
		if (comparison != 0)
			return comparison < 0;

		return this.minor <= ceiling.minor;
	}

	public boolean isAbove(ElectrumProtocolVersion ceiling) {
		return !isAtOrBelow(ceiling);
	}

	public boolean isAtOrAbove(ElectrumProtocolVersion floor) {
		return compareTo(floor) >= 0;
	}

	/** @return true when this version is at or above <code>minimum</code> and at or below <code>maximum</code> */
	public boolean isWithin(ElectrumProtocolVersion minimum, ElectrumProtocolVersion maximum) {
		return isAtOrAbove(minimum) && isAtOrBelow(maximum);
	}

	@Override
	public String toString() {
		return this.patchSpecified
				? this.major + "." + this.minor + "." + this.patch
				: this.major + "." + this.minor;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other)
			return true;

		if (!(other instanceof ElectrumProtocolVersion))
			return false;

		ElectrumProtocolVersion that = (ElectrumProtocolVersion) other;
		return this.major == that.major && this.minor == that.minor && this.patch == that.patch;
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.major, this.minor, this.patch);
	}
}
