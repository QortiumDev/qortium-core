package org.qortium.at;

import org.ciyam.at.AtLogger;

public class ChainAtLoggerFactory implements org.ciyam.at.AtLoggerFactory {

	private static ChainAtLoggerFactory instance;

	private ChainAtLoggerFactory() {
	}

	public static synchronized ChainAtLoggerFactory getInstance() {
		if (instance == null)
			instance = new ChainAtLoggerFactory();

		return instance;
	}

	@Override
	public AtLogger create(final Class<?> loggerName) {
		return ChainAtLogger.create(loggerName);
	}

}
