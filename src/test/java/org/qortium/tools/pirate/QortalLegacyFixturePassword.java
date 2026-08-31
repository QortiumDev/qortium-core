package org.qortium.tools.pirate;

import org.json.JSONObject;
import org.qortium.crypto.Crypto;
import org.qortium.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Acceptance-only bridge for the protected Qortal-derived fixtures. */
public final class QortalLegacyFixturePassword {

	private QortalLegacyFixturePassword() {
	}

	public static void main(String[] args) {
		if (args.length != 2) {
			System.err.println("[error] usage-invalid");
			System.exit(2);
		}
		try {
			Path metadata = Path.of(args[0]);
			if (!metadata.isAbsolute() || Files.isSymbolicLink(metadata)
					|| !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS))
				throw new IllegalArgumentException();
			int outputDescriptor = Integer.parseInt(args[1]);
			if (outputDescriptor < 3)
				throw new IllegalArgumentException();
			JSONObject parsed = new JSONObject(Files.readString(metadata));
			byte[] entropy = Base58.decode(parsed.getString("entropy58"));
			if (entropy.length != 32)
				throw new IllegalArgumentException();
			ByteArrayOutputStream material = new ByteArrayOutputStream();
			material.write("ARRRWalletEncryption".getBytes(StandardCharsets.UTF_8));
			material.write(entropy);
			byte[] password = Crypto.digest(material.toByteArray());
			byte[] encodedPassword = Base58.encode(password).getBytes(StandardCharsets.UTF_8);
			try (OutputStream output = Files.newOutputStream(
					Path.of("/proc/self/fd", Integer.toString(outputDescriptor)))) {
				output.write(encodedPassword);
			}
			java.util.Arrays.fill(entropy, (byte) 0);
			java.util.Arrays.fill(password, (byte) 0);
			java.util.Arrays.fill(encodedPassword, (byte) 0);
		} catch (Throwable e) {
			System.err.println("[error] fixture-password-derivation-failed");
			System.exit(1);
		}
	}
}
