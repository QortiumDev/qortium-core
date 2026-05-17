package org.qortal.repository.hsqldb;

import org.qortal.data.account.AccountTrustStatus;

final class HSQLDBTrustWeightSql {

	private HSQLDBTrustWeightSql() {
	}

	static String activeTrustStatusSql(String snapshotAlias) {
		return "COALESCE(" + snapshotAlias + ".mapped_trust_status, 0)";
	}

	static String effectiveWeightSql(String trustStatusSql, String rawWeightSql) {
		StringBuilder sql = new StringBuilder(256);
		sql.append("CASE ").append(trustStatusSql).append(" ");
		for (AccountTrustStatus status : AccountTrustStatus.values()) {
			sql.append("WHEN ").append(status.getValue())
					.append(" THEN ").append(rawWeightSql)
					.append(" * ").append(status.getVoteWeightPercent()).append(" / 100 ");
		}
		sql.append("ELSE 0 END");

		return sql.toString();
	}

	static String trustWeightPercentSql(String trustStatusSql) {
		StringBuilder sql = new StringBuilder(256);
		sql.append("CASE ").append(trustStatusSql).append(" ");
		for (AccountTrustStatus status : AccountTrustStatus.values()) {
			sql.append("WHEN ").append(status.getValue())
					.append(" THEN ").append(status.getVoteWeightPercent()).append(" ");
		}
		sql.append("ELSE 0 END");

		return sql.toString();
	}
}
