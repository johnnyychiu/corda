package net.corda.demobench.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.LogManager;

public class LoggingConfig {

    public LoggingConfig() throws IOException {
        try (InputStream input = getLoggingProperties()) {
            LogManager manager = LogManager.getLogManager();
            manager.readConfiguration(input);
        }
    }

    private static InputStream getLoggingProperties() throws IOException {
        InputStream input = LoggingConfig.class.getResourceAsStream("logging.properties");
        if (input == null) {
            File javaHome = new File(System.getProperty("java.home"));
            input = new FileInputStream(new File(new File(javaHome, "lib"), "logging.properties"));
        }
        return input;
    }

}
