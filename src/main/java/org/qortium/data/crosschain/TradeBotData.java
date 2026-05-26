package org.qortium.data.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.json.JSONObject;
import org.qortium.utils.Base58;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotData {

	private byte[] tradePrivateKey;

	private String acctName;
	private String tradeState;

	// Internal use - not shown via API
	@XmlTransient
	@Schema(hidden = true)
	private int tradeStateValue;

	private String creatorAddress;
	private String atAddress;

	private long timestamp;

	private long localAssetId;

	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long localAmount;

	private byte[] tradeLocalPublicKey;
	private byte[] tradeLocalPublicKeyHash;
	private String tradeLocalAddress;

	private byte[] secret;
	private byte[] hashOfSecret;

	private String foreignBlockchain;
	private byte[] tradeForeignPublicKey;
	private byte[] tradeForeignPublicKeyHash;

	@Schema(description = "amount in foreign blockchain currency", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long foreignAmount;

	// Never expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private String foreignKey;

	private String offeredForeignBlockchain;
	private byte[] offeredTradeForeignPublicKey;
	private byte[] offeredTradeForeignPublicKeyHash;

	@Schema(description = "amount in offered foreign blockchain currency", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private Long offeredForeignAmount;

	// Never expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private String offeredForeignKey;

	private String requestedForeignBlockchain;
	private byte[] requestedTradeForeignPublicKey;
	private byte[] requestedTradeForeignPublicKeyHash;

	@Schema(description = "amount in requested foreign blockchain currency", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private Long requestedForeignAmount;

	// Never expose this via API
	@XmlTransient
	@Schema(hidden = true)
	private String requestedForeignKey;

	private byte[] lastTransactionSignature;
	private Integer lockTimeA;
	private Integer lockTimeB;
	private Integer fillSlotIndex;

	// Could be a foreign-chain or local-chain account identifier.
	private byte[] receivingAccountInfo;

	private byte[] offeredForeignReceivingAccountInfo;
	private byte[] requestedForeignReceivingAccountInfo;

	protected TradeBotData() {
		/* JAXB */
	}

	public TradeBotData(byte[] tradePrivateKey, String acctName, String tradeState, int tradeStateValue,
			String creatorAddress, String atAddress,
			long timestamp, long localAssetId, long localAmount,
			byte[] tradeLocalPublicKey, byte[] tradeLocalPublicKeyHash, String tradeLocalAddress,
			byte[] secret, byte[] hashOfSecret,
			String foreignBlockchain, byte[] tradeForeignPublicKey, byte[] tradeForeignPublicKeyHash,
			long foreignAmount, String foreignKey,
			byte[] lastTransactionSignature, Integer lockTimeA, byte[] receivingAccountInfo) {
		this(tradePrivateKey, acctName, tradeState, tradeStateValue,
				creatorAddress, atAddress, timestamp, localAssetId, localAmount,
				tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress,
				secret, hashOfSecret, foreignBlockchain, tradeForeignPublicKey, tradeForeignPublicKeyHash,
				foreignAmount, foreignKey, lastTransactionSignature, lockTimeA, null, receivingAccountInfo);
	}

	public TradeBotData(byte[] tradePrivateKey, String acctName, String tradeState, int tradeStateValue,
			String creatorAddress, String atAddress,
			long timestamp, long localAssetId, long localAmount,
			byte[] tradeLocalPublicKey, byte[] tradeLocalPublicKeyHash, String tradeLocalAddress,
			byte[] secret, byte[] hashOfSecret,
			String foreignBlockchain, byte[] tradeForeignPublicKey, byte[] tradeForeignPublicKeyHash,
			long foreignAmount, String foreignKey,
			byte[] lastTransactionSignature, Integer lockTimeA, Integer fillSlotIndex, byte[] receivingAccountInfo) {
		this.tradePrivateKey = tradePrivateKey;
		this.acctName = acctName;
		this.tradeState = tradeState;
		this.tradeStateValue = tradeStateValue;
		this.creatorAddress = creatorAddress;
		this.atAddress = atAddress;
		this.timestamp = timestamp;
		this.localAssetId = localAssetId;
		this.localAmount = localAmount;
		this.tradeLocalPublicKey = tradeLocalPublicKey;
		this.tradeLocalPublicKeyHash = tradeLocalPublicKeyHash;
		this.tradeLocalAddress = tradeLocalAddress;
		this.secret = secret;
		this.hashOfSecret = hashOfSecret;
		this.foreignBlockchain = foreignBlockchain;
		this.tradeForeignPublicKey = tradeForeignPublicKey;
		this.tradeForeignPublicKeyHash = tradeForeignPublicKeyHash;
		this.foreignAmount = foreignAmount;
		this.foreignKey = foreignKey;
		this.lastTransactionSignature = lastTransactionSignature;
		this.lockTimeA = lockTimeA;
		this.fillSlotIndex = fillSlotIndex;
		this.receivingAccountInfo = receivingAccountInfo;
	}

	public byte[] getTradePrivateKey() {
		return this.tradePrivateKey;
	}

	public String getAcctName() {
		return this.acctName;
	}

	public String getState() {
		return this.tradeState;
	}

	public void setState(String state) {
		this.tradeState = state;
	}

	public int getStateValue() {
		return this.tradeStateValue;
	}

	public void setStateValue(int stateValue) {
		this.tradeStateValue = stateValue;
	}

	public String getCreatorAddress() {
		return this.creatorAddress;
	}

	public String getAtAddress() {
		return this.atAddress;
	}

	public void setAtAddress(String atAddress) {
		this.atAddress = atAddress;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

	public long getLocalAssetId() {
		return this.localAssetId;
	}

	public long getLocalAmount() {
		return this.localAmount;
	}

	public byte[] getTradeLocalPublicKey() {
		return this.tradeLocalPublicKey;
	}

	public byte[] getTradeLocalPublicKeyHash() {
		return this.tradeLocalPublicKeyHash;
	}

	public String getTradeLocalAddress() {
		return this.tradeLocalAddress;
	}

	public byte[] getSecret() {
		return this.secret;
	}

	public byte[] getHashOfSecret() {
		return this.hashOfSecret;
	}

	public String getForeignBlockchain() {
		return this.foreignBlockchain;
	}

	public byte[] getTradeForeignPublicKey() {
		return this.tradeForeignPublicKey;
	}

	public byte[] getTradeForeignPublicKeyHash() {
		return this.tradeForeignPublicKeyHash;
	}

	public long getForeignAmount() {
		return this.foreignAmount;
	}

	public String getForeignKey() {
		return this.foreignKey;
	}

	public String getOfferedForeignBlockchain() {
		return this.offeredForeignBlockchain;
	}

	public void setOfferedForeignBlockchain(String offeredForeignBlockchain) {
		this.offeredForeignBlockchain = offeredForeignBlockchain;
	}

	public byte[] getOfferedTradeForeignPublicKey() {
		return this.offeredTradeForeignPublicKey;
	}

	public void setOfferedTradeForeignPublicKey(byte[] offeredTradeForeignPublicKey) {
		this.offeredTradeForeignPublicKey = offeredTradeForeignPublicKey;
	}

	public byte[] getOfferedTradeForeignPublicKeyHash() {
		return this.offeredTradeForeignPublicKeyHash;
	}

	public void setOfferedTradeForeignPublicKeyHash(byte[] offeredTradeForeignPublicKeyHash) {
		this.offeredTradeForeignPublicKeyHash = offeredTradeForeignPublicKeyHash;
	}

	public Long getOfferedForeignAmount() {
		return this.offeredForeignAmount;
	}

	public void setOfferedForeignAmount(Long offeredForeignAmount) {
		this.offeredForeignAmount = offeredForeignAmount;
	}

	public String getOfferedForeignKey() {
		return this.offeredForeignKey;
	}

	public void setOfferedForeignKey(String offeredForeignKey) {
		this.offeredForeignKey = offeredForeignKey;
	}

	public String getRequestedForeignBlockchain() {
		return this.requestedForeignBlockchain;
	}

	public void setRequestedForeignBlockchain(String requestedForeignBlockchain) {
		this.requestedForeignBlockchain = requestedForeignBlockchain;
	}

	public byte[] getRequestedTradeForeignPublicKey() {
		return this.requestedTradeForeignPublicKey;
	}

	public void setRequestedTradeForeignPublicKey(byte[] requestedTradeForeignPublicKey) {
		this.requestedTradeForeignPublicKey = requestedTradeForeignPublicKey;
	}

	public byte[] getRequestedTradeForeignPublicKeyHash() {
		return this.requestedTradeForeignPublicKeyHash;
	}

	public void setRequestedTradeForeignPublicKeyHash(byte[] requestedTradeForeignPublicKeyHash) {
		this.requestedTradeForeignPublicKeyHash = requestedTradeForeignPublicKeyHash;
	}

	public Long getRequestedForeignAmount() {
		return this.requestedForeignAmount;
	}

	public void setRequestedForeignAmount(Long requestedForeignAmount) {
		this.requestedForeignAmount = requestedForeignAmount;
	}

	public String getRequestedForeignKey() {
		return this.requestedForeignKey;
	}

	public void setRequestedForeignKey(String requestedForeignKey) {
		this.requestedForeignKey = requestedForeignKey;
	}

	public byte[] getLastTransactionSignature() {
		return this.lastTransactionSignature;
	}

	public void setLastTransactionSignature(byte[] lastTransactionSignature) {
		this.lastTransactionSignature = lastTransactionSignature;
	}

	public Integer getLockTimeA() {
		return this.lockTimeA;
	}

	public void setLockTimeA(Integer lockTimeA) {
		this.lockTimeA = lockTimeA;
	}

	public Integer getLockTimeB() {
		return this.lockTimeB;
	}

	public void setLockTimeB(Integer lockTimeB) {
		this.lockTimeB = lockTimeB;
	}

	public Integer getFillSlotIndex() {
		return this.fillSlotIndex;
	}

	public void setFillSlotIndex(Integer fillSlotIndex) {
		this.fillSlotIndex = fillSlotIndex;
	}

	public byte[] getReceivingAccountInfo() {
		return this.receivingAccountInfo;
	}

	public byte[] getOfferedForeignReceivingAccountInfo() {
		return this.offeredForeignReceivingAccountInfo;
	}

	public void setOfferedForeignReceivingAccountInfo(byte[] offeredForeignReceivingAccountInfo) {
		this.offeredForeignReceivingAccountInfo = offeredForeignReceivingAccountInfo;
	}

	public byte[] getRequestedForeignReceivingAccountInfo() {
		return this.requestedForeignReceivingAccountInfo;
	}

	public void setRequestedForeignReceivingAccountInfo(byte[] requestedForeignReceivingAccountInfo) {
		this.requestedForeignReceivingAccountInfo = requestedForeignReceivingAccountInfo;
	}

	public JSONObject toJson() {
		JSONObject jsonObject = new JSONObject();
		jsonObject.put("tradePrivateKey", Base58.encode(this.getTradePrivateKey()));
		jsonObject.put("acctName", this.getAcctName());
		jsonObject.put("tradeState", this.getState());
		jsonObject.put("tradeStateValue", this.getStateValue());
		jsonObject.put("creatorAddress", this.getCreatorAddress());
		jsonObject.put("atAddress", this.getAtAddress());
		jsonObject.put("timestamp", this.getTimestamp());
		jsonObject.put("localAssetId", this.getLocalAssetId());
		jsonObject.put("localAmount", this.getLocalAmount());
		if (this.getTradeLocalPublicKey() != null) jsonObject.put("tradeLocalPublicKey", Base58.encode(this.getTradeLocalPublicKey()));
		if (this.getTradeLocalPublicKeyHash() != null) jsonObject.put("tradeLocalPublicKeyHash", Base58.encode(this.getTradeLocalPublicKeyHash()));
		jsonObject.put("tradeLocalAddress", this.getTradeLocalAddress());
		if (this.getSecret() != null) jsonObject.put("secret", Base58.encode(this.getSecret()));
		if (this.getHashOfSecret() != null) jsonObject.put("hashOfSecret", Base58.encode(this.getHashOfSecret()));
		jsonObject.put("foreignBlockchain", this.getForeignBlockchain());
		if (this.getTradeForeignPublicKey() != null) jsonObject.put("tradeForeignPublicKey", Base58.encode(this.getTradeForeignPublicKey()));
		if (this.getTradeForeignPublicKeyHash() != null) jsonObject.put("tradeForeignPublicKeyHash", Base58.encode(this.getTradeForeignPublicKeyHash()));
		jsonObject.put("foreignKey", this.getForeignKey());
		jsonObject.put("foreignAmount", this.getForeignAmount());
		jsonObject.put("offeredForeignBlockchain", this.getOfferedForeignBlockchain());
		if (this.getOfferedTradeForeignPublicKey() != null) jsonObject.put("offeredTradeForeignPublicKey", Base58.encode(this.getOfferedTradeForeignPublicKey()));
		if (this.getOfferedTradeForeignPublicKeyHash() != null) jsonObject.put("offeredTradeForeignPublicKeyHash", Base58.encode(this.getOfferedTradeForeignPublicKeyHash()));
		jsonObject.put("offeredForeignAmount", this.getOfferedForeignAmount());
		jsonObject.put("offeredForeignKey", this.getOfferedForeignKey());
		jsonObject.put("requestedForeignBlockchain", this.getRequestedForeignBlockchain());
		if (this.getRequestedTradeForeignPublicKey() != null) jsonObject.put("requestedTradeForeignPublicKey", Base58.encode(this.getRequestedTradeForeignPublicKey()));
		if (this.getRequestedTradeForeignPublicKeyHash() != null) jsonObject.put("requestedTradeForeignPublicKeyHash", Base58.encode(this.getRequestedTradeForeignPublicKeyHash()));
		jsonObject.put("requestedForeignAmount", this.getRequestedForeignAmount());
		jsonObject.put("requestedForeignKey", this.getRequestedForeignKey());
		if (this.getLastTransactionSignature() != null) jsonObject.put("lastTransactionSignature", Base58.encode(this.getLastTransactionSignature()));
		jsonObject.put("lockTimeA", this.getLockTimeA());
		jsonObject.put("lockTimeB", this.getLockTimeB());
		jsonObject.put("fillSlotIndex", this.getFillSlotIndex());
		if (this.getReceivingAccountInfo() != null) jsonObject.put("receivingAccountInfo", Base58.encode(this.getReceivingAccountInfo()));
		if (this.getOfferedForeignReceivingAccountInfo() != null) jsonObject.put("offeredForeignReceivingAccountInfo", Base58.encode(this.getOfferedForeignReceivingAccountInfo()));
		if (this.getRequestedForeignReceivingAccountInfo() != null) jsonObject.put("requestedForeignReceivingAccountInfo", Base58.encode(this.getRequestedForeignReceivingAccountInfo()));
		return jsonObject;
	}

	public static TradeBotData fromJson(JSONObject json) {
		TradeBotData tradeBotData = new TradeBotData(
				json.isNull("tradePrivateKey") ? null : Base58.decode(json.getString("tradePrivateKey")),
				json.isNull("acctName") ? null : json.getString("acctName"),
				json.isNull("tradeState") ? null : json.getString("tradeState"),
				json.isNull("tradeStateValue") ? null : json.getInt("tradeStateValue"),
				json.isNull("creatorAddress") ? null : json.getString("creatorAddress"),
				json.isNull("atAddress") ? null : json.getString("atAddress"),
				json.isNull("timestamp") ? null : json.getLong("timestamp"),
				json.getLong("localAssetId"),
				json.getLong("localAmount"),
				json.isNull("tradeLocalPublicKey") ? null : Base58.decode(json.getString("tradeLocalPublicKey")),
				json.isNull("tradeLocalPublicKeyHash") ? null : Base58.decode(json.getString("tradeLocalPublicKeyHash")),
				json.isNull("tradeLocalAddress") ? null : json.getString("tradeLocalAddress"),
				json.isNull("secret") ? null : Base58.decode(json.getString("secret")),
				json.isNull("hashOfSecret") ? null : Base58.decode(json.getString("hashOfSecret")),
				json.isNull("foreignBlockchain") ? null : json.getString("foreignBlockchain"),
				json.isNull("tradeForeignPublicKey") ? null : Base58.decode(json.getString("tradeForeignPublicKey")),
				json.isNull("tradeForeignPublicKeyHash") ? null : Base58.decode(json.getString("tradeForeignPublicKeyHash")),
				json.isNull("foreignAmount") ? null : json.getLong("foreignAmount"),
				json.isNull("foreignKey") ? null : json.getString("foreignKey"),
				json.isNull("lastTransactionSignature") ? null : Base58.decode(json.getString("lastTransactionSignature")),
				json.isNull("lockTimeA") ? null : json.getInt("lockTimeA"),
				json.isNull("fillSlotIndex") ? null : json.getInt("fillSlotIndex"),
				json.isNull("receivingAccountInfo") ? null : Base58.decode(json.getString("receivingAccountInfo"))
		);

		tradeBotData.setOfferedForeignBlockchain(json.isNull("offeredForeignBlockchain") ? null : json.getString("offeredForeignBlockchain"));
		tradeBotData.setOfferedTradeForeignPublicKey(json.isNull("offeredTradeForeignPublicKey") ? null : Base58.decode(json.getString("offeredTradeForeignPublicKey")));
		tradeBotData.setOfferedTradeForeignPublicKeyHash(json.isNull("offeredTradeForeignPublicKeyHash") ? null : Base58.decode(json.getString("offeredTradeForeignPublicKeyHash")));
		tradeBotData.setOfferedForeignAmount(json.isNull("offeredForeignAmount") ? null : json.getLong("offeredForeignAmount"));
		tradeBotData.setOfferedForeignKey(json.isNull("offeredForeignKey") ? null : json.getString("offeredForeignKey"));
		tradeBotData.setRequestedForeignBlockchain(json.isNull("requestedForeignBlockchain") ? null : json.getString("requestedForeignBlockchain"));
		tradeBotData.setRequestedTradeForeignPublicKey(json.isNull("requestedTradeForeignPublicKey") ? null : Base58.decode(json.getString("requestedTradeForeignPublicKey")));
		tradeBotData.setRequestedTradeForeignPublicKeyHash(json.isNull("requestedTradeForeignPublicKeyHash") ? null : Base58.decode(json.getString("requestedTradeForeignPublicKeyHash")));
		tradeBotData.setRequestedForeignAmount(json.isNull("requestedForeignAmount") ? null : json.getLong("requestedForeignAmount"));
		tradeBotData.setRequestedForeignKey(json.isNull("requestedForeignKey") ? null : json.getString("requestedForeignKey"));
		tradeBotData.setLockTimeB(json.isNull("lockTimeB") ? null : json.getInt("lockTimeB"));
		tradeBotData.setOfferedForeignReceivingAccountInfo(json.isNull("offeredForeignReceivingAccountInfo") ? null : Base58.decode(json.getString("offeredForeignReceivingAccountInfo")));
		tradeBotData.setRequestedForeignReceivingAccountInfo(json.isNull("requestedForeignReceivingAccountInfo") ? null : Base58.decode(json.getString("requestedForeignReceivingAccountInfo")));

		return tradeBotData;
	}

	// Mostly for debugging
	public String toString() {
		return String.format("%s: %s (%d)", this.atAddress, this.tradeState, this.tradeStateValue);
	}

}
