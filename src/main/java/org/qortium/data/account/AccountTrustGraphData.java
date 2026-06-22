package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

/**
 * A shaped trust graph for one rating category: the account {@code nodes} with their derived trust
 * standing, plus the directed rating {@code edges} between them. Either the full active set or a
 * neighbourhood scoped to a {@code root} account and traversal {@code depth}.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustGraphData {

	private AccountRatingCategory category;
	private List<AccountTrustGraphNodeData> nodes;
	private List<AccountTrustGraphEdgeData> edges;

	protected AccountTrustGraphData() {
	}

	public AccountTrustGraphData(AccountRatingCategory category, List<AccountTrustGraphNodeData> nodes,
			List<AccountTrustGraphEdgeData> edges) {
		this.category = category;
		this.nodes = nodes == null ? new ArrayList<>() : nodes;
		this.edges = edges == null ? new ArrayList<>() : edges;
	}

	public AccountRatingCategory getCategory() {
		return this.category;
	}

	public List<AccountTrustGraphNodeData> getNodes() {
		return this.nodes;
	}

	public List<AccountTrustGraphEdgeData> getEdges() {
		return this.edges;
	}
}
