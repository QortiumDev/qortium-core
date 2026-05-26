package org.qortium.test.api;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferPrivsTransactionData;
import org.qortium.data.transaction.UpdatePollTransactionData;
import org.qortium.data.transaction.VoteOnPollTransactionData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.group.Group;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import org.qortium.utils.Amounts;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.StringWriter;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class TransactionDataJsonTests extends ApiCommon {

	@Test
	public void testTransferPrivsJsonIncludesSenderAndRecipientAddresses() throws JAXBException {
		PrivateKeyAccount alice = Common.getTestAccount(null, "alice");
		String bobAddress = Common.getTestAccount(null, "bob").getAddress();

		BaseTransactionData baseTransactionData = new BaseTransactionData(System.currentTimeMillis(), Group.NO_GROUP,
				alice.getPublicKey(), Amounts.MULTIPLIER, null);
		TransferPrivsTransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData, bobAddress);

		String json = marshal(transactionData);

		assertTrue(json.contains("\"type\":\"TRANSFER_PRIVS\""));
		assertTrue(json.contains("\"creatorAddress\":\"" + alice.getAddress() + "\""));
		assertTrue(json.contains("\"recipient\":\"" + bobAddress + "\""));
	}

	@Test
	public void testVoteOnPollJsonIncludesVoterAddress() throws JAXBException {
		PrivateKeyAccount alice = Common.getTestAccount(null, "alice");

		BaseTransactionData baseTransactionData = new BaseTransactionData(System.currentTimeMillis(), Group.NO_GROUP,
				alice.getPublicKey(), Amounts.MULTIPLIER, null);
		VoteOnPollTransactionData transactionData = new VoteOnPollTransactionData(baseTransactionData, 123, 1);

		String json = marshal(transactionData);

		assertTrue(json.contains("\"type\":\"VOTE_ON_POLL\""));
		assertTrue(json.contains("\"creatorAddress\":\"" + alice.getAddress() + "\""));
		assertTrue(json.contains("\"voterAddress\":\"" + alice.getAddress() + "\""));
		assertTrue(json.contains("\"voterPublicKey\""));
		assertTrue(json.contains("\"pollId\":123"));
		assertTrue(json.contains("\"optionIndex\":1"));
	}

	@Test
	public void testUpdatePollJsonIncludesOwnerAddress() throws JAXBException {
		PrivateKeyAccount alice = Common.getTestAccount(null, "alice");

		BaseTransactionData baseTransactionData = new BaseTransactionData(System.currentTimeMillis(), Group.NO_GROUP,
				alice.getPublicKey(), Amounts.MULTIPLIER, null);
		UpdatePollTransactionData transactionData = new UpdatePollTransactionData(baseTransactionData, 123, "test-poll",
				"Updated description", List.of(new PollOptionData("Yes"), new PollOptionData("No")), null);

		String json = marshal(transactionData);

		assertTrue(json.contains("\"type\":\"UPDATE_POLL\""));
		assertTrue(json.contains("\"creatorAddress\":\"" + alice.getAddress() + "\""));
		assertTrue(json.contains("\"ownerAddress\":\"" + alice.getAddress() + "\""));
		assertTrue(json.contains("\"ownerPublicKey\""));
		assertTrue(json.contains("\"pollId\":123"));
		assertTrue(json.contains("\"newPollName\":\"test-poll\""));
		assertTrue(json.contains("\"newDescription\":\"Updated description\""));
	}

	private static String marshal(TransactionData transactionData) throws JAXBException {
		JAXBContext context = JAXBContextFactory.createContext(new Class[] {TransactionData.class}, null);
		Marshaller marshaller = context.createMarshaller();
		marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");
		marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, false);

		StringWriter writer = new StringWriter();
		marshaller.marshal(transactionData, writer);
		return writer.toString();
	}

}
