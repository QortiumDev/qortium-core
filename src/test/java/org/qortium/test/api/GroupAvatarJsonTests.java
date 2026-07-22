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
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupAvatarJsonTests {
	@Test
	public void testAvatarIsNestedDescriptorAndInternalSignatureIsNotExposed() throws Exception {
		GroupData group = new GroupData(7, "Qowner", "example", "description", 1L, null, true,
				ApprovalThreshold.ONE, 0, 1, new byte[64], 0, "example");
		byte[] signature = new byte[64]; Arrays.fill(signature, (byte) 1); group.setAvatarSignature(signature);
		group.setAvatar(new AvatarData(signature, Service.THUMBNAIL, "owner-name", "qortium-group-avatar-v1-7"));
		JAXBContext context = JAXBContextFactory.createContext(new Class[] { GroupData.class }, null);
		Marshaller marshaller = context.createMarshaller(); marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");
		marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, false);
		StringWriter output = new StringWriter(); marshaller.marshal(group, output);
		String json = output.toString();
		assertTrue(json.contains("\"avatar\"")); assertTrue(json.contains("qortium-group-avatar-v1-7"));
		assertFalse(json.contains("avatarSignature"));
	}
}
