package org.qortium.test.crosschain.apps;

import com.google.common.hash.HashCode;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyAddress;
import org.qortium.crosschain.BitcoinyHTLC;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class BuildHTLC {

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: BuildHTLC <coin> <refund-P2PKH> <amount> <redeem-P2PKH> <HASH160-of-secret> <locktime>"));
		System.err.println("where: " + Common.bitcoinyUsage());
		System.err.println(String.format("example: BuildHTLC -l "
				+ "msAfaDaJ8JiprxxFaAXEEPxKK3JaZCYpLv \\\n"
				+ "\t0.00008642 \\\n"
				+ "\tmrBpZYYGYMwUa8tRjTiXfP1ySqNXszWN5h \\\n"
				+ "\tdaf59884b4d1aec8c1b17102530909ee43c0151a \\\n"
				+ "\t1600000000"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 6 || args.length > 6)
			usage(null);

		Common.init();

		Bitcoiny bitcoiny = null;
		NetworkParameters params = null;

		BitcoinyAddress refundAddress = null;
		Coin amount = null;
		BitcoinyAddress redeemAddress = null;
		byte[] hashOfSecret = null;
		int lockTime = 0;

		int argIndex = 0;
		try {
			bitcoiny = Common.getBitcoiny(args[argIndex++]);
			params = bitcoiny.getNetworkParameters();

			refundAddress = BitcoinyAddress.fromString(params, args[argIndex++]);
			if (!refundAddress.isP2PKH())
				usage("Refund address must be in P2PKH form");

			amount = Coin.parseCoin(args[argIndex++]);

			redeemAddress = BitcoinyAddress.fromString(params, args[argIndex++]);
			if (!redeemAddress.isP2PKH())
				usage("Redeem address must be in P2PKH form");

			hashOfSecret = HashCode.fromString(args[argIndex++]).asBytes();
			if (hashOfSecret.length != 20)
				usage("Hash of secret must be 20 bytes");

			lockTime = Integer.parseInt(args[argIndex++]);
			int refundTimeoutDelay = lockTime - (int) (System.currentTimeMillis() / 1000L);
			if (refundTimeoutDelay < 600 || refundTimeoutDelay > 30 * 24 * 60 * 60)
				usage("Locktime (seconds) should be at between 10 minutes and 1 month from now");
		} catch (IllegalArgumentException e) {
			usage(String.format("Invalid argument %d: %s", argIndex, e.getMessage()));
		}

		System.out.println(String.format("Using %s", bitcoiny.getBlockchainProvider().getNetId()));

		Coin p2shFee = Coin.valueOf(Common.getP2shFee(bitcoiny));
		if (p2shFee.isZero())
			return;

		System.out.println(String.format("Refund address: %s", refundAddress));
		System.out.println(String.format("Amount: %s", amount.toPlainString()));
		System.out.println(String.format("Redeem address: %s", redeemAddress));
		System.out.println(String.format("Refund/redeem miner's fee: %s", bitcoiny.format(p2shFee)));
		System.out.println(String.format("Script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC), lockTime));
		System.out.println(String.format("Hash of secret: %s", HashCode.fromBytes(hashOfSecret)));

		byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(refundAddress.getPayload(), lockTime, redeemAddress.getPayload(), hashOfSecret);
		System.out.println(String.format("Raw script bytes: %s", HashCode.fromBytes(redeemScriptBytes)));

		String p2shAddress = bitcoiny.deriveP2shAddress(redeemScriptBytes);
		System.out.println(String.format("P2SH address: %s", p2shAddress));

		amount = amount.add(p2shFee);

		// Fund P2SH
		System.out.println(String.format("\nYou need to fund %s with %s (includes redeem/refund fee of %s)",
				p2shAddress, bitcoiny.format(amount), bitcoiny.format(p2shFee)));
	}

}
