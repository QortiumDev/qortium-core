package org.qortium.test.common;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.repository.Repository;
import org.qortium.utils.Base58;

public class TestAccount extends PrivateKeyAccount {

	public final String accountName;
	public final boolean isRewardShare;

	public TestAccount(Repository repository, String accountName, String privateKey, boolean isRewardShare) {
		super(repository, Base58.decode(privateKey));

		this.accountName = accountName;
		this.isRewardShare = isRewardShare;
	}

	public TestAccount(Repository repository, TestAccount testAccount) {
		this(repository, testAccount.accountName, Base58.encode(testAccount.getPrivateKey()), testAccount.isRewardShare);
	}

}
