package org.qortium.api.model;

import org.qortium.controller.Controller;
import org.qortium.controller.OnlineAccountsManager;
import org.qortium.controller.Synchronizer;
import org.qortium.network.Network;
import org.qortium.network.NetworkData;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class NodeStatus {

	public final boolean isMintingPossible;
	public final boolean isSynchronizing;

	// Not always present
	public final Integer syncPercent;

	public final int numberOfConnections;

	public final int numberOfDataConnections;

	public final int height;

	public NodeStatus() {
		this.isMintingPossible = OnlineAccountsManager.getInstance().hasActiveOnlineAccountSignatures();

		this.syncPercent = Synchronizer.getInstance().getSyncPercent();
		this.isSynchronizing = Synchronizer.getInstance().isSynchronizing();

		this.numberOfConnections = Network.getInstance().getImmutableHandshakedPeers().size();

		this.numberOfDataConnections = NetworkData.getInstance().getImmutableHandshakedPeers().size();

		this.height = Controller.getInstance().getChainHeight();
	}

}
