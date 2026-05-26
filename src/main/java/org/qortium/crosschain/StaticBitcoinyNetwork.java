package org.qortium.crosschain;

import org.bitcoinj.core.NetworkParameters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class StaticBitcoinyNetwork implements BitcoinyNetwork {

	private final String name;
	private final Supplier<NetworkParameters> paramsSupplier;
	private final Collection<ElectrumX.Server> servers;
	private final String genesisHash;
	private final String chainId;
	private final AtomicLong feeRequired;
	private final Long fixedP2shFee;

	public StaticBitcoinyNetwork(String name, Supplier<NetworkParameters> paramsSupplier,
			Collection<ElectrumX.Server> servers, String genesisHash, long feeRequired, Long fixedP2shFee) {
		this(name, paramsSupplier, servers, genesisHash, null, feeRequired, fixedP2shFee);
	}

	public StaticBitcoinyNetwork(String name, Supplier<NetworkParameters> paramsSupplier,
			Collection<ElectrumX.Server> servers, String genesisHash, String chainId, long feeRequired, Long fixedP2shFee) {
		this.name = name;
		this.paramsSupplier = paramsSupplier;
		this.servers = Collections.unmodifiableList(new ArrayList<>(servers));
		this.genesisHash = genesisHash;
		this.chainId = resolveChainId(genesisHash, chainId);
		this.feeRequired = new AtomicLong(feeRequired);
		this.fixedP2shFee = fixedP2shFee;
	}

	private static String resolveChainId(String genesisHash, String chainId) {
		if (chainId != null)
			return Bip122ChainId.normalize(chainId);

		return genesisHash == null ? null : Bip122ChainId.fromBlockHash(genesisHash);
	}

	public static StaticBitcoinyNetwork mainnet(Supplier<NetworkParameters> paramsSupplier,
			Collection<ElectrumX.Server> servers, String genesisHash, long feeRequired) {
		return new StaticBitcoinyNetwork("MAIN", paramsSupplier, servers, genesisHash, feeRequired, null);
	}

	public static StaticBitcoinyNetwork mainnet(Supplier<NetworkParameters> paramsSupplier,
			Collection<ElectrumX.Server> servers, String genesisHash, String chainId, long feeRequired) {
		return new StaticBitcoinyNetwork("MAIN", paramsSupplier, servers, genesisHash, chainId, feeRequired, null);
	}

	public static StaticBitcoinyNetwork nonMainnet(String name, Supplier<NetworkParameters> paramsSupplier,
			Collection<ElectrumX.Server> servers, String genesisHash, long feeRequired, long p2shFee) {
		return new StaticBitcoinyNetwork(name, paramsSupplier, servers, genesisHash, feeRequired, p2shFee);
	}

	public static StaticBitcoinyNetwork nonMainnet(String name, Supplier<NetworkParameters> paramsSupplier,
			Collection<ElectrumX.Server> servers, String genesisHash, String chainId, long feeRequired, long p2shFee) {
		return new StaticBitcoinyNetwork(name, paramsSupplier, servers, genesisHash, chainId, feeRequired, p2shFee);
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public NetworkParameters getParams() {
		return this.paramsSupplier.get();
	}

	@Override
	public Collection<ElectrumX.Server> getServers() {
		return this.servers;
	}

	@Override
	public String getGenesisHash() {
		return this.genesisHash;
	}

	@Override
	public String getChainId() {
		if (this.chainId != null)
			return this.chainId;

		return Bip122ChainId.fromBlockHash(this.getParams().getGenesisBlock().getHashAsString());
	}

	@Override
	public long getP2shFee(Long timestamp) {
		return this.fixedP2shFee != null ? this.fixedP2shFee : this.getFeeRequired();
	}

	@Override
	public long getFeeRequired() {
		return this.feeRequired.get();
	}

	@Override
	public void setFeeRequired(long feeRequired) {
		this.feeRequired.set(feeRequired);
	}

}
