package org.qortium.test.minting;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.Common;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;

import static org.junit.Assert.assertEquals;

public class RewardShareDisableWindowTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testRewardShareStillValidWithoutInheritedDisableWindow() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, AccountUtils.createRewardShare(repository, "alice", "bob", 0));
			assertEquals("Reward share should remain valid without inherited disable-window triggers", ValidationResult.OK, transaction.isValid());
		}
	}
}
