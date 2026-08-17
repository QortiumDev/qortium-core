package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.chat.PrivateGroupChatPublicService;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class PrivateGroupChatControlPage {

	@Schema(description = "closed group id")
	public final int txGroupId;

	@Schema(description = "bounded signed QPGC control records")
	public final List<PrivateGroupChatControlResponse> controls;

	@Schema(description = "opaque cursor for the final returned record, or null for an empty page")
	public final String nextCursor;

	@Schema(description = "whether more matching records may exist beyond nextCursor")
	public final boolean hasMore;

	protected PrivateGroupChatControlPage() {
		this.txGroupId = 0;
		this.controls = List.of();
		this.nextCursor = null;
		this.hasMore = false;
	}

	public PrivateGroupChatControlPage(PrivateGroupChatPublicService.ControlPage page) {
		this.txGroupId = page.getGroupId();
		List<PrivateGroupChatControlResponse> responses = new ArrayList<>(page.getRecords().size());
		for (PrivateGroupChatPublicService.ControlRecord record : page.getRecords())
			responses.add(new PrivateGroupChatControlResponse(record));
		this.controls = List.copyOf(responses);
		this.nextCursor = page.getNextCursor() == null ? null : page.getNextCursor().encode();
		this.hasMore = page.hasMore();
	}
}
