package org.qortium.api.model.crosschain;

import com.google.common.hash.HashCode;
import org.qortium.crosschain.WalletSpendContextUtxo;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class ForeignWalletSpendContextUtxo {

	private String address;
	private int height;
	private List<Integer> path;
	private String pathAsString;
	private String scriptPubKeyHex;
	private String txHash;
	private int outputIndex;
	private String value;

	public ForeignWalletSpendContextUtxo() {
	}

	public ForeignWalletSpendContextUtxo(WalletSpendContextUtxo output) {
		this.address = output.getAddress();
		this.height = output.getHeight();
		this.path = output.getPath();
		this.pathAsString = output.getPathAsString();
		this.scriptPubKeyHex = HashCode.fromBytes(output.getScriptPubKey()).toString();
		this.txHash = HashCode.fromBytes(output.getTransactionHash()).toString();
		this.outputIndex = output.getOutputIndex();
		this.value = Long.toString(output.getValue());
	}

	public String getAddress() {
		return this.address;
	}

	public int getHeight() {
		return this.height;
	}

	public List<Integer> getPath() {
		return this.path;
	}

	public String getPathAsString() {
		return this.pathAsString;
	}

	public String getScriptPubKeyHex() {
		return this.scriptPubKeyHex;
	}

	public String getTxHash() {
		return this.txHash;
	}

	public int getOutputIndex() {
		return this.outputIndex;
	}

	public String getValue() {
		return this.value;
	}
}
