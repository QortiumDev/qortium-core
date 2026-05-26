package org.qortium.data.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.json.JSONObject;
import org.qortium.utils.Base58;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotFillData {

	private String atAddress;
	private int slotIndex;
	private String state;
	private long timestamp;
	private String partnerAddress;
	private byte[] partnerForeignPublicKeyHash;
	private byte[] hashOfSecret;
	private int lockTimeA;

	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long localAmount;

	@Schema(description = "amount in foreign blockchain currency", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	private long foreignAmount;

	private String p2shAddress;

	protected TradeBotFillData() {
		/* JAXB */
	}

	public TradeBotFillData(String atAddress, int slotIndex, String state, long timestamp, String partnerAddress,
			byte[] partnerForeignPublicKeyHash, byte[] hashOfSecret, int lockTimeA, long localAmount,
			long foreignAmount, String p2shAddress) {
		this.atAddress = atAddress;
		this.slotIndex = slotIndex;
		this.state = state;
		this.timestamp = timestamp;
		this.partnerAddress = partnerAddress;
		this.partnerForeignPublicKeyHash = partnerForeignPublicKeyHash;
		this.hashOfSecret = hashOfSecret;
		this.lockTimeA = lockTimeA;
		this.localAmount = localAmount;
		this.foreignAmount = foreignAmount;
		this.p2shAddress = p2shAddress;
	}

	public String getAtAddress() {
		return this.atAddress;
	}

	public int getSlotIndex() {
		return this.slotIndex;
	}

	public void setSlotIndex(int slotIndex) {
		this.slotIndex = slotIndex;
	}

	public String getState() {
		return this.state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

	public String getPartnerAddress() {
		return this.partnerAddress;
	}

	public byte[] getPartnerForeignPublicKeyHash() {
		return this.partnerForeignPublicKeyHash;
	}

	public byte[] getHashOfSecret() {
		return this.hashOfSecret;
	}

	public int getLockTimeA() {
		return this.lockTimeA;
	}

	public long getLocalAmount() {
		return this.localAmount;
	}

	public long getForeignAmount() {
		return this.foreignAmount;
	}

	public String getP2shAddress() {
		return this.p2shAddress;
	}

	public JSONObject toJson() {
		JSONObject jsonObject = new JSONObject();
		jsonObject.put("atAddress", this.getAtAddress());
		jsonObject.put("slotIndex", this.getSlotIndex());
		jsonObject.put("state", this.getState());
		jsonObject.put("timestamp", this.getTimestamp());
		jsonObject.put("partnerAddress", this.getPartnerAddress());
		if (this.getPartnerForeignPublicKeyHash() != null)
			jsonObject.put("partnerForeignPublicKeyHash", Base58.encode(this.getPartnerForeignPublicKeyHash()));
		if (this.getHashOfSecret() != null)
			jsonObject.put("hashOfSecret", Base58.encode(this.getHashOfSecret()));
		jsonObject.put("lockTimeA", this.getLockTimeA());
		jsonObject.put("localAmount", this.getLocalAmount());
		jsonObject.put("foreignAmount", this.getForeignAmount());
		jsonObject.put("p2shAddress", this.getP2shAddress());
		return jsonObject;
	}

	public static TradeBotFillData fromJson(JSONObject json) {
		return new TradeBotFillData(
				json.isNull("atAddress") ? null : json.getString("atAddress"),
				json.isNull("slotIndex") ? 0 : json.getInt("slotIndex"),
				json.isNull("state") ? null : json.getString("state"),
				json.isNull("timestamp") ? 0L : json.getLong("timestamp"),
				json.isNull("partnerAddress") ? null : json.getString("partnerAddress"),
				json.isNull("partnerForeignPublicKeyHash") ? null : Base58.decode(json.getString("partnerForeignPublicKeyHash")),
				json.isNull("hashOfSecret") ? null : Base58.decode(json.getString("hashOfSecret")),
				json.isNull("lockTimeA") ? 0 : json.getInt("lockTimeA"),
				json.isNull("localAmount") ? 0L : json.getLong("localAmount"),
				json.isNull("foreignAmount") ? 0L : json.getLong("foreignAmount"),
				json.isNull("p2shAddress") ? null : json.getString("p2shAddress"));
	}
}
