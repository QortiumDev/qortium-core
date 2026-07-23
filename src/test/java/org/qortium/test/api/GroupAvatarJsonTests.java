package org.qortium.test.api;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.junit.Test;
import org.qortium.data.group.GroupData;
import org.qortium.data.avatar.AvatarData;
import org.qortium.arbitrary.misc.Service;
import org.qortium.group.Group.ApprovalThreshold;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;
import java.io.StringWriter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupAvatarJsonTests {
	@Test
	public void testAvatarPointerIsNestedAndNoSignatureIsExposed() throws Exception {
		GroupData group = new GroupData(7, "Qowner", "example", "description", 1L, null, true,
				ApprovalThreshold.ONE, 0, 1, new byte[64], 0, "example");
		group.setAvatar(new AvatarData(Service.THUMBNAIL, "owner-name", "custom-id"));
		JAXBContext context = JAXBContextFactory.createContext(new Class[] { GroupData.class }, null);
		Marshaller marshaller = context.createMarshaller(); marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");
		marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, false);
		StringWriter output = new StringWriter(); marshaller.marshal(group, output);
		String json = output.toString();
		// The avatar pointer (service, name, identifier) is exposed as a nested object.
		assertTrue(json.contains("\"avatar\""));
		assertTrue(json.contains("owner-name"));
		assertTrue(json.contains("custom-id"));
		// No transaction signature is part of the avatar model any more.
		assertFalse(json.toLowerCase().contains("signature"));
	}
}
