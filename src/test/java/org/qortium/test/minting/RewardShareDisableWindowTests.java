package org.qortium.test.minting;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.block.BlockChain;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.Common;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;

import java.util.Map;

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
	@SuppressWarnings("unchecked")
	public void testRewardShareStillValidInsideFormerDisableWindow() throws DataException, IllegalAccessException {
		Map<String, Long> featureTriggers = (Map<String, Long>) FieldUtils.readField(BlockChain.getInstance(), "featureTriggers", true);
		Long previousDisableHeight = featureTriggers.put("disableRewardshareHeight", 1L);
		Long previousEnableHeight = featureTriggers.put("enableRewardshareHeight", 100L);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, AccountUtils.createRewardShare(repository, "alice", "bob", 0));
			assertEquals("Reward share should remain valid inside the former disable window", ValidationResult.OK, transaction.isValid());
		} finally {
			if (previousDisableHeight == null)
				featureTriggers.remove("disableRewardshareHeight");
			else
				featureTriggers.put("disableRewardshareHeight", previousDisableHeight);

			if (previousEnableHeight == null)
				featureTriggers.remove("enableRewardshareHeight");
			else
				featureTriggers.put("enableRewardshareHeight", previousEnableHeight);
		}
	}
}
