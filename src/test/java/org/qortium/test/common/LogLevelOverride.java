package org.qortium.test.common;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

public class LogLevelOverride implements AutoCloseable {

	private final LoggerContext loggerContext;
	private final Configuration config;
	private final String loggerName;
	private final LoggerConfig loggerConfig;
	private final Level previousLevel;
	private final boolean addedLoggerConfig;

	private LogLevelOverride(Class<?> loggerClass, Level level) {
		this.loggerContext = (LoggerContext) LogManager.getContext(false);
		this.config = this.loggerContext.getConfiguration();
		this.loggerName = loggerClass.getName();

		LoggerConfig existingLoggerConfig = this.config.getLoggerConfig(this.loggerName);
		this.addedLoggerConfig = !existingLoggerConfig.getName().equals(this.loggerName);

		if (this.addedLoggerConfig) {
			this.loggerConfig = new LoggerConfig(this.loggerName, level, true);
			this.previousLevel = null;
			this.config.addLogger(this.loggerName, this.loggerConfig);
		} else {
			this.loggerConfig = existingLoggerConfig;
			this.previousLevel = this.loggerConfig.getLevel();
			this.loggerConfig.setLevel(level);
		}

		this.loggerContext.updateLoggers(this.config);
	}

	public static LogLevelOverride setLevel(Class<?> loggerClass, Level level) {
		return new LogLevelOverride(loggerClass, level);
	}

	@Override
	public void close() {
		if (this.addedLoggerConfig) {
			this.config.removeLogger(this.loggerName);
		} else {
			this.loggerConfig.setLevel(this.previousLevel);
		}

		this.loggerContext.updateLoggers(this.config);
	}

}
