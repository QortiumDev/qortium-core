package org.qortium.repository.hsqldb;

import org.qortium.arbitrary.misc.Service;
import org.qortium.data.avatar.AvatarData;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps an avatar pointer to/from three consecutive columns: (service SMALLINT, name, identifier). */
public final class HSQLDBAvatars {

	private HSQLDBAvatars() {
	}

	/** Reads an avatar pointer starting at {@code serviceColumn}; returns null when that column is SQL NULL. */
	public static AvatarData read(ResultSet resultSet, int serviceColumn) throws SQLException {
		int service = resultSet.getInt(serviceColumn);
		if (resultSet.wasNull())
			return null;
		return new AvatarData(Service.valueOf(service), resultSet.getString(serviceColumn + 1), resultSet.getString(serviceColumn + 2));
	}

	/** Binds an avatar pointer to {@code <prefix>_service/_name/_identifier}; binds all NULL when the pointer is null. */
	public static void bind(HSQLDBSaver saver, String prefix, AvatarData avatar) {
		saver.bind(prefix + "_service", avatar == null ? null : (Integer) avatar.getService().value)
				.bind(prefix + "_name", avatar == null ? null : avatar.getName())
				.bind(prefix + "_identifier", avatar == null ? null : (avatar.getIdentifier() == null ? "" : avatar.getIdentifier()));
	}
}
