package org.qortium.test.crosschain.apps;

import org.bitcoinj.base.Coin;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinySignedTransaction;

public class Pay {

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: Pay <coin> <xprv58> <recipient> <amount>"));
		System.err.println("where: " + Common.bitcoinyUsage());
		System.err.println(String.format("example: Pay -l "
				+ "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ \\\n"
				+ "\tmsAfaDaJ8JiprxxFaAXEEPxKK3JaZCYpLv \\\n"
				+ "\t0.00008642"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 4 || args.length > 4)
			usage(null);

		Common.init();

		Bitcoiny bitcoiny = null;

		String xprv58 = null;
		String address = null;
		Coin amount = null;

		int argIndex = 0;
		try {
			bitcoiny = Common.getBitcoiny(args[argIndex++]);

			xprv58 = args[argIndex++];
			if (!bitcoiny.isValidDeterministicKey(xprv58))
				usage("xprv invalid");

			address = args[argIndex++];
			if (!bitcoiny.isValidAddress(address))
				usage("Address invalid");

			amount = Coin.parseCoin(args[argIndex++]);
		} catch (IllegalArgumentException e) {
			usage(String.format("Invalid argument %d: %s", argIndex, e.getMessage()));
		}

		System.out.println(String.format("Using %s", bitcoiny.getBlockchainProvider().getNetId()));

		System.out.println(String.format("Address: %s", address));
		System.out.println(String.format("Amount: %s", amount.toPlainString()));

		BitcoinySignedTransaction transaction = bitcoiny.buildSpendTransaction(xprv58, address, amount.value);
		if (transaction == null) {
			System.err.println("Insufficent funds");
			System.exit(1);
		}

		Common.broadcastTransaction(bitcoiny, transaction);
	}

}
