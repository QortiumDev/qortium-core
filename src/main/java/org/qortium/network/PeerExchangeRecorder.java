package org.qortium.network;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.qortium.settings.Settings;

/**
 * Diagnostic, default-off recorder of received PEERS gossip messages.
 *
 * When {@link Settings#isRecordPeerExchangeEnabled()} is true, every received PEERS message
 * (chain and data networks) is appended as a single JSON line (JSONL) to "peer-exchange.jsonl"
 * in the process working directory. When the setting is false, this class performs no file I/O
 * and no allocation beyond the boolean check.
 */
public class PeerExchangeRecorder {

	private static final Logger LOGGER = LogManager.getLogger(PeerExchangeRecorder.class);

	private static final Path OUTPUT_PATH = Paths.get("peer-exchange.jsonl");

	private static final PeerExchangeRecorder INSTANCE = new PeerExchangeRecorder();

	private boolean warned = false;

	private PeerExchangeRecorder() {
	}

	public static PeerExchangeRecorder getInstance() {
		return INSTANCE;
	}

	/**
	 * Append one JSONL record describing a received PEERS message. No-op (and no allocation) unless
	 * the recordPeerExchange setting is enabled. Never throws into the network path.
	 *
	 * @param layer                "chain" or "data"
	 * @param peer                 the sending peer
	 * @param advertisedAddresses  the RAW advertised list (index 0 is the sender's listen-port marker and is skipped)
	 */
	public void record(String layer, Peer peer, List<PeerAddress> advertisedAddresses) {
		if (!Settings.getInstance().isRecordPeerExchangeEnabled())
			return;

		JSONObject record = new JSONObject();
		record.put("at", Instant.now().toString());
		record.put("layer", layer);

		PeerAddress fromAddress = peer.getPeerData().getAddress();
		record.put("fromPeer", fromAddress.toString());
		record.put("fromNodeId", peer.getPeersNodeId());
		record.put("transport", fromAddress.isI2P() ? "I2P" : "IP");

		JSONArray peers = new JSONArray();
		if (advertisedAddresses != null && advertisedAddresses.size() > 1) {
			// Skip index 0: it is the sender's listen-port marker with an empty address.
			for (int i = 1; i < advertisedAddresses.size(); ++i) {
				PeerAddress address = advertisedAddresses.get(i);
				if (address != null)
					peers.put(address.toString());
			}
		}
		record.put("peers", peers);

		String line = record.toString() + "\n";

		synchronized (this) {
			try (BufferedWriter writer = Files.newBufferedWriter(OUTPUT_PATH, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				writer.write(line);
			} catch (IOException e) {
				if (!this.warned) {
					this.warned = true;
					LOGGER.warn("Unable to write peer-exchange diagnostic file {}: {}", OUTPUT_PATH, e.getMessage());
				}
			}
		}
	}
}
