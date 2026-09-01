package org.qortium.test.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.core.NetworkParameters;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.crosschain.BitcoinyChainSpecs;
import org.qortium.crosschain.BitcoinyScript;
import org.qortium.crosschain.BitcoinySignedTransaction;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.UnspentOutput;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Public synthetic vectors shared with Home's noble/secp256k1 signer tests.
 * The two implementations deliberately share only fixed inputs and expected
 * bytes: Core's independent bitcoinj builder must reproduce the same legacy
 * transaction for every chain Home intends to sign locally.
 */
public class ForeignWalletSigningVectorTests {

	private static final long INPUT_VALUE = 100_000L;
	private static final long OUTPUT_VALUE = 89_964L;
	private static final long FEE_PER_BYTE = 52L;

	private static final List<SigningVector> VECTORS = List.of(
			new SigningVector(
					"BITCOIN",
					"xprv9s21ZrQH143K2qjVdn864NSY7aNESo88ao1ZnALHmYdTLUywN8cMNQwbXDZs6N7YmfTaHoHX2FCTiDtUjsLH22EAqxLtaPQZHe8QMTtJUSm",
					"187v1EG87d2fwnXuiGb815r1g4kReiPsSf",
					"1MqnCQUgyHN461YPQ1yi7DmTc2NiF7XiFc",
					"5d07166e9fd48565f6f5b5c5d6dda14daa6b477e470ab2dec9cf8dc4505b7f11",
					"0100000001117f5b50c48dcfc9deb20a477e476baa4da1ddd6c5b5f5f66585d49f6e16075d000000006a47304402207cd448817547dfbd103515d15c6590381edccb6d2a6788f074526739c16313a20220550bdf1f06f39def7e2bd659504b3104ca28b6ea93b1fa5b23d5decb76329abd0121028d93e306d698001f979293d68b635e692e74a750178039ebae77a33c03311912ffffffff016c5f0100000000001976a914e49c35c89163fafb453ace35d49e9d96ed5386ae88ac00000000",
					"b4dca0ef3381bdb0636662534538e88330091fe5ee9109118546e57fad298568"),
			new SigningVector(
					"LITECOIN",
					"xprv9s21ZrQH143K3FAiM4CHbm7cbYguCyYCdLMGW5YEXPz5KtPBJwNFa3oMkXBVvUP9UEjNahQS1aJwb3xJpMwa42KdFeJGsVFxotcqB9MYjzy",
					"LZNdy6Wf9p37wqDGkZttE9HnwJCm25byov",
					"LY4RJZ9qXEEZHMMxbpwc6gjCVfxScZfwgN",
					"461cb098b3b14e518bebeb2be0d0088bc0612afc1d65c0f53e73b4621c2d6f02",
					"0100000001026f2d1c62b4733ef5c0651dfc2a61c08b08d0e02bebeb8b514eb1b398b01c46000000006a473044022029304629cd972862656f60e8fb62cf9dd5f218850666cc5e2eb4eeb77277badd02207da08dff1da87a4ea4b36cd480649657d4b3b36dc80eed842313bab52a2bff2b012102677f55d844e1834bdc7d32b6473f16e313792af3b094b183d8a9ab50396784e2ffffffff016c5f0100000000001976a9148ccc0c390a3c4ad488c38e90399b6b006a5ad2c188ac00000000",
					"e8375aa35875e045e8fe69006749fb02a74ac7abea19954a13410bb955017ffd"),
			new SigningVector(
					"DOGECOIN",
					"dgpv51eADS3spNJhAMcBW9cpeTfBCfxPbN9fp3j8RRnfscuAfTiEKvTkTZcRsRxqpyhpASaWmvg32EAWXfDaPHBakTDsWkvmozaWZS8D3jYEfw3",
					"D78W6j6FBCCJuwgopkRhkSnU43xZ5wpcfg",
					"D6gpDUUqMqZh9ikFqzMc77HawPxbWN2mFL",
					"9036d8ca07336f477b941921920a76c678af1d8a7c111c0d1a093bcb4fc83ddb",
					"0100000001db3dc84fcb3b091a0d1c117c8a1daf78c6760a922119947b476f3307cad83690000000006b483045022100ea020e0201c9f8e2bb3c47ffc1faa20e17c1d959eb25d9a5b26810b8b96f49990220321b6078c3675e43e1661f0a65e01940ac00536e1c4765833840ab2286abcc620121037da2d11d1af5e92e9329ec91553c9fb8acafc6a2b5621e8f419c649853159d6effffffff016c5f0100000000001976a91410f7cf6208aaa93e20cf026df40de45985f6507288ac00000000",
					"67ba7edc828602b5b154076e2ff2bb6178b160d8ac1d8a920ade8a33f9b799de"),
			new SigningVector(
					"DIGIBYTE",
					"xprv9s21ZrQH143K2f4b8o6Dki6hoQ1HiJpLKXWW3skwyzZhMbyVKTuy37nGEnkUoXZBVAbSnKnjDMyUszgbAdCt1GaNwAB2c2xmmM4YC6P4PrN",
					"DKd6VzQ1dzBXF9V6Na9oufjrHYtq1HKvRi",
					"DUCXQGMV8jdjcsqSHT2yx1iN6fh3zq87MY",
					"dbad351e2c25e4f34f59bed614044cd8c9be41345a68acd448840e4420f8ad16",
					"010000000116adf820440e8448d4ac685a3441bec9d84c0414d6be594ff3e4252c1e35addb000000006b4830450221008a1c08ef5ee1d247156ef49a6d2d414f02be0281c7ee2bf22cfc1f807a525a4902205a56813d1747c49a224b4ff07d59db1e5bcd4b42900d8478a019593731bbdd7e0121021ce007a9103d30437cbfa330f0b80fcaae4b3ff894c710583b2d618491d0dd0affffffff016c5f0100000000001976a914fcf0e51334259f440ef0e8746b4b42fc0b0e0c3588ac00000000",
					"b93597634bfed8c6f634faff2bbaed53b173dbae7a4fff42d38cd163e1038009"),
			new SigningVector(
					"RAVENCOIN",
					"xprv9s21ZrQH143K35c59uc1SwUbP7VvFDqU6fBAAaW2yYfBFAr7XeSprASi6U39EtwZgoJUADUCrA3XemKW1fsHyVLrLKkCZKT4WQn6ZZ5THCq",
					"RStiQL2PC2VGSiyJbzXtkh11o7wcWf6R2N",
					"REjiyZUdVFdAARrdJoMLMRGTkzshzgwNoS",
					"87a99f7fc357f6aee1df04ef4cc116cf9211fccdfe95faef3f6474082a445b09",
					"0100000001095b442a0874643feffa95fecdfc1192cf16c14cef04dfe1aef657c37f9fa987000000006a47304402203bfe92afa78981b094d213ba02f9f82fc9a1a4c9cd038357be8bdcc18a8edd750220453edc23327d28a6691230ec25bd5d89ee7b3289f52fa06854ac6d53963b23330121027afbd1dd29a0784d6f3726ada86d53e5d304ef69c52afc7f9b3d530bea49befaffffffff016c5f0100000000001976a9143bdd3c57914dc3eaa58c873aa708fe15f73f4d1088ac00000000",
					"61c279f09f6793a21789fc1405b26ad0bc7a2a5d44372bcbe509d43d8be7be54"),
			new SigningVector(
					"DASH",
					"xprv9s21ZrQH143K2DsjBVfwwDiMQ9J2NLjQ3MPGbKKoLc8qFhSDg3KuRiiyvUc3Yt7nPzBpVXU8mWS81JVEyRGEBxQ4hYzpMPj2SfFpcfzssZk",
					"XwPhDwHqFvJdzSvQN7c6nopqgDYTNrcVau",
					"XqdLckBx3a1vMcVb3cPnXAbg3o2LNZHZwn",
					"b23cf9ca425943a7d853e3cfbf92cd121449ad439e8e6487084eae9524b913e1",
					"0100000001e113b92495ae4e0887648e9e43ad491412cd92bfcfe353d8a7435942caf93cb2000000006a4730440220490ff282fab925e7c3dfc197a3765fb7ba42de0b252daf12143816f209cd288002205b4221da3ca7b51628f0517b6983b234f87a38d589d723fdafb71962a110c321012103828488399bb7d44181b738ff0a92460366af9855404e644f8c6b86ae00225cb8ffffffff016c5f0100000000001976a914a3e040390a9052cf947d97492bc7d7fea13ba77188ac00000000",
					"ef2cf48a8e9f1aa541d829646ccf5f6403fe9594810d38100ed0508ec92a1aeb"),
			new SigningVector(
					"NAMECOIN",
					"xprv9s21ZrQH143K3A757NXKXbQ3tRobgRS7r5nYmHmMrkw6M7Q1bdmox63Pfh8BZLTfr3zhATJxQSenK8odmuKcAR25faYysb718ndff9GifAU",
					"NH9FVHNUW1t3xPC9LkDQrK5r13CaGebXCY",
					"NB1ZdbZgD8gr9n4TCmi5BtztTb43vZXu8P",
					"ff3927e36ab204139b6d9521da018f1189b3b362e77440840cc1b4e81062a41c",
					"01000000011ca46210e8b4c10c844074e762b3b389118f01da21956d9b1304b26ae32739ff000000006a473044022017b205d07b1a1f35c75f53dc26c43ef7de3a384a49c89f30884a420352b86cae02200fb36693ba5c25304d030778013f2ea7a3b2e0c1acfce66dab86fe9a789aadb0012103f210f2645f9225a461f27835c55ddd7ff100b95a559781b82480970dcb9ae21effffffff016c5f0100000000001976a9149e55b3914fefd696f4865c6a975cd6b4afdb0ec988ac00000000",
					"69c37d40b722923e0ae3f544526808c59bc7e4083855f98478c3df519690693a"),
			new SigningVector(
					"FIRO",
					"xprv9s21ZrQH143K43GBt3XcqzXSwNSkLZjF7xCNNcALHTSmmQ1eX5rjLxqthemB8UMiH5XWWzGsTLynS9fekygwdSswKSZsUF5qAxfQWszaSgE",
					"a1Sn72TRQvjjTbsrRLJXvxTtQLq6NtvnJt",
					"a7gU9X3GpMbDMwMiYiNiaixT1WmVJCR1NQ",
					"f98b58c49921a585e69271b0367c20809db50d3686a5a0be4c588b9a4e91e5a0",
					"0100000001a0e5914e9a8b584cbea0a586360db59d80207c36b07192e685a52199c4588bf9000000006a4730440220495f39f5c3b689d3cac81d8480e2e6575c2600822aeb3598ef7be255c28697000220623b47aa28d302f63dcebe9d13e910647e02eefc0df266efb3e166ec1c3f2bd20121021693c8101243f8ddac815943d2e11681d505ce03fd5aa56bb3a1e90dfb9d5be7ffffffff016c5f0100000000001976a9144c68636f8cc97802cd235da98d8c56e21c276eea88ac00000000",
					"fa469276ff7791181b10c98a6e4f52d0b5e55e6b5f4a7378d0163fa3254257e4")
	);

	@BeforeClass
	public static void beforeClass() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testHomeAndCoreProduceIdenticalLegacyTransactions() throws ForeignBlockchainException {
		for (SigningVector vector : VECTORS) {
			ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromStringRequired(vector.blockchain);
			NetworkParameters params = entry.getBitcoinySpec().getNetwork(BitcoinyChainSpecs.MAIN).getParams();
			MockBitcoinyBlockchainProvider provider = new MockBitcoinyBlockchainProvider(vector.blockchain + "-signing-vector");
			TestBitcoiny bitcoiny = new TestBitcoiny(params, provider, entry.getCurrencyCode());
			byte[] fundingScript = BitcoinyScript.scriptPubKey(params, vector.fundingAddress);
			UnspentOutput input = new UnspentOutput(
					HashCode.fromString(vector.previousTransactionHash).asBytes(),
					0,
					100,
					INPUT_VALUE,
					fundingScript,
					vector.fundingAddress);
			provider.addUnspentOutput(vector.fundingAddress, input);
			provider.addUnspentOutput(fundingScript, input);
			assertTrue(vector.blockchain, bitcoiny.getWalletAddresses(vector.xprv58).contains(vector.fundingAddress));
			assertEquals(vector.blockchain, INPUT_VALUE, bitcoiny.getConfirmedBalance(vector.fundingAddress));

			BitcoinySignedTransaction signed = bitcoiny.buildSpendTransaction(
					vector.xprv58,
					vector.recipientAddress,
					OUTPUT_VALUE,
					FEE_PER_BYTE);

			assertNotNull(vector.blockchain, signed);
			assertEquals(vector.blockchain, vector.rawTransactionHex, HashCode.fromBytes(signed.getRawTransaction()).toString());
			assertEquals(vector.blockchain, vector.transactionId, signed.getTxHash());
		}
	}

	private static final class SigningVector {
		private final String blockchain;
		private final String xprv58;
		private final String fundingAddress;
		private final String recipientAddress;
		private final String previousTransactionHash;
		private final String rawTransactionHex;
		private final String transactionId;

		private SigningVector(String blockchain, String xprv58, String fundingAddress, String recipientAddress,
				String previousTransactionHash, String rawTransactionHex, String transactionId) {
			this.blockchain = blockchain;
			this.xprv58 = xprv58;
			this.fundingAddress = fundingAddress;
			this.recipientAddress = recipientAddress;
			this.previousTransactionHash = previousTransactionHash;
			this.rawTransactionHex = rawTransactionHex;
			this.transactionId = transactionId;
		}
	}
}
