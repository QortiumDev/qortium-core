package org.qortium.api.model;

import org.qortium.controller.Controller;
import org.qortium.network.Network;
import org.qortium.settings.Settings;
import org.qortium.utils.NTP;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class NodeInfo {

	public Long currentTimestamp;
	public long uptime;
	public String buildVersion;
	public long buildTimestamp;
	public String nodeId;
	public boolean isTestNet;
	public String type;

	public NodeInfo() {
	}

	/** Build the canonical live node-info response shared by the API and gateway. */
	public static NodeInfo current() {
		NodeInfo nodeInfo = new NodeInfo();

		nodeInfo.currentTimestamp = NTP.getTime();
		nodeInfo.uptime = System.currentTimeMillis() - Controller.startTime;
		nodeInfo.buildVersion = Controller.getInstance().getVersionString();
		nodeInfo.buildTimestamp = Controller.getInstance().getBuildTimestamp();
		nodeInfo.nodeId = Network.getInstance().getOurNodeId();
		nodeInfo.isTestNet = Settings.getInstance().isTestNet();
		nodeInfo.type = getNodeType();

		return nodeInfo;
	}

	private static String getNodeType() {
		if (Settings.getInstance().isLite())
			return "lite";

		if (Settings.getInstance().isTopOnly())
			return "topOnly";

		return "full";
	}

}
