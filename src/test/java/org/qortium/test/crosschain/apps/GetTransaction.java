package org.qortium.test.crosschain.apps;

import com.google.common.hash.HashCode;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyTransaction;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.settings.Settings;

import java.security.Security;
import java.util.List;

public class GetTransaction {

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
	}

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: GetTransaction <coin> <tx-hash>"));
		System.err.println("where: " + Common.bitcoinyUsage());
		System.err.println(String.format("example (mainnet): GetTransaction -b 816407e79568f165f13e09e9912c5f2243e0a23a007cec425acedc2e89284660"));
		System.err.println(String.format("example (testnet): GetTransaction -b 3bfd17a492a4e3d6cb7204e17e20aca6c1ab82e1828bd1106eefbaf086fb8a4e"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length != 2)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		Settings.fileInstance("settings-test.json");

		Bitcoiny bitcoiny = null;
		byte[] transactionId = null;

		int argIndex = 0;
		try {
			bitcoiny = Common.getBitcoiny(args[argIndex++]);

			transactionId = HashCode.fromString(args[argIndex++]).asBytes();
		} catch (NumberFormatException | AddressFormatException e) {
			usage(String.format("Argument format exception: %s", e.getMessage()));
		}

		System.out.println(String.format("Using %s", bitcoiny.getBlockchainProvider().getNetId()));

		// Grab all outputs from transaction
		List<BitcoinyTransaction.Output> fundingOutputs;
		try {
			fundingOutputs = bitcoiny.getOutputs(transactionId);
		} catch (ForeignBlockchainException e) {
			System.out.println(String.format("Transaction not found (or error occurred)"));
			return;
		}

		System.out.println(String.format("Found %d output%s", fundingOutputs.size(), (fundingOutputs.size() != 1 ? "s" : "")));

		for (int outputIndex = 0; outputIndex < fundingOutputs.size(); ++outputIndex) {
			BitcoinyTransaction.Output fundingOutput = fundingOutputs.get(outputIndex);
			System.out.println(String.format("Output %d: %s", outputIndex, bitcoiny.format(fundingOutput.value)));
		}
	}

}
