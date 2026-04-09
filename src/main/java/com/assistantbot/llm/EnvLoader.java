package com.assistantbot.llm;

import com.assistantbot.AssistantMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads environment variables with fallback to a .env file.
 * The .env file is lazily loaded on first access and cached.
 * OS environment variables always take precedence over .env values.
 */
public class EnvLoader {

    private static Map<String, String> dotenvValues;

    /**
     * Get an environment variable value. Checks System.getenv() first,
     * then falls back to the .env file in the working directory.
     *
     * @param name the variable name
     * @return the value, or null if not found in either source
     */
    public static String get(String name) {
        String sysValue = System.getenv(name);
        if (sysValue != null && !sysValue.isBlank()) {
            return sysValue;
        }
        return loadDotenv().get(name);
    }

    private static synchronized Map<String, String> loadDotenv() {
        if (dotenvValues != null) {
            return dotenvValues;
        }

        dotenvValues = new HashMap<>();
        Path envFile = Path.of(".env");

        if (!Files.exists(envFile)) {
            AssistantMod.LOGGER.debug(".env file not found in working directory, using OS env vars only");
            return dotenvValues;
        }

        try {
            AssistantMod.LOGGER.info("Loading .env file from {}", envFile.toAbsolutePath());
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();

                // Skip blank lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Strip optional "export " prefix
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }

                // Split on first '=' only (values may contain '=')
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }

                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

                // Strip surrounding quotes (single or double)
                if (value.length() >= 2) {
                    char first = value.charAt(0);
                    char last = value.charAt(value.length() - 1);
                    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                        value = value.substring(1, value.length() - 1);
                    }
                }

                dotenvValues.put(key, value);
            }
            AssistantMod.LOGGER.info("Loaded {} values from .env file", dotenvValues.size());
        } catch (IOException e) {
            AssistantMod.LOGGER.warn("Failed to read .env file: {}", e.getMessage());
        }

        return dotenvValues;
    }
}
