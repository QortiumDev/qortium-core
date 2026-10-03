import com.fasterxml.jackson.databind.ObjectMapper;
import monero.wallet.MoneroWalletFull;
import monero.wallet.model.MoneroWalletConfig;
import monero.daemon.model.MoneroNetworkType;
import org.qortium.crosschain.monero.MoneroKeys;
import org.qortium.crosschain.monero.MoneroNativeLoader;
import java.nio.file.Path;
import java.util.HexFormat;

/** Run against the shaded JAR, in memory, with the public fixture and no daemon. */
class PackagedSmoke {
    public static void main(String[] args) throws Exception {
        var fixture = new ObjectMapper().readTree(Path.of("src/test/resources/monero/derivation-v1.json").toFile())
                .get("fixtures").get(3);
        MoneroNativeLoader.load();
        try (var keys = new MoneroKeys(HexFormat.of().parseHex(fixture.get("coinSeed").asText()))) {
            var wallet = MoneroWalletFull.createWallet(new MoneroWalletConfig().setNetworkType(MoneroNetworkType.MAINNET)
                    .setPrivateSpendKey(keys.spendHex()).setLanguage("English"));
            try {
                if (!fixture.get("address").asText().equals(wallet.getPrimaryAddress())
                        || !keys.viewHex().equals(wallet.getPrivateViewKey())) throw new AssertionError("Packaged derivation mismatch");
            } finally { wallet.close(false); }
        }
        System.out.println("PASS shaded JAR: pinned JNI loads, canonical address/view key, in-memory close, no daemon");
    }
}
