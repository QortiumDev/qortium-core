package org.qortal.test.api;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferPrivsTransactionData;
import org.qortal.data.transaction.VoteOnPollTransactionData;
import org.qortal.group.Group;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;
import org.qortal.utils.Amounts;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.StringWriter;

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
		VoteOnPollTransactionData transactionData = new VoteOnPollTransactionData(baseTransactionData, "test-poll", 1);

		String json = marshal(transactionData);

		assertTrue(json.contains("\"type\":\"VOTE_ON_POLL\""));
		assertTrue(json.contains("\"creatorAddress\":\"" + alice.getAddress() + "\""));
		assertTrue(json.contains("\"voterAddress\":\"" + alice.getAddress() + "\""));
		assertTrue(json.contains("\"voterPublicKey\""));
		assertTrue(json.contains("\"pollName\":\"test-poll\""));
		assertTrue(json.contains("\"optionIndex\":1"));
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
