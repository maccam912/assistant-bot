# .env File Support Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a hand-rolled `.env` file parser so LLM config doesn't require OS-level environment variables.

**Architecture:** A single `EnvLoader` utility class provides `get(name)` that checks `System.getenv()` first, then falls back to a lazily-parsed `.env` file. `LlmClient.requireEnv` switches from `System.getenv` to `EnvLoader.get`. A `.env.example` template and `.gitignore` update prevent accidental secret commits.

**Tech Stack:** Java 17+, no new dependencies

**Spec:** `docs/superpowers/specs/2026-04-08-dotenv-support-design.md`

---

## Chunk 1: Implementation

### Task 1: Create `EnvLoader` class

**Files:**
- Create: `src/main/java/com/assistantbot/llm/EnvLoader.java`

- [ ] **Step 1: Create the EnvLoader class**

```java
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/assistantbot/llm/EnvLoader.java
git commit -m "feat: add EnvLoader with .env file parsing and lazy loading"
```

---

### Task 2: Wire `EnvLoader` into `LlmClient`

**Files:**
- Modify: `src/main/java/com/assistantbot/llm/LlmClient.java:189`

- [ ] **Step 1: Update `requireEnv` to use `EnvLoader.get`**

In `LlmClient.java`, change line 189 from:
```java
String value = System.getenv(name);
```
to:
```java
String value = EnvLoader.get(name);
```

No import needed — `EnvLoader` is in the same package.

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/assistantbot/llm/LlmClient.java
git commit -m "feat: use EnvLoader in LlmClient for .env fallback"
```

---

### Task 3: Add `.env.example` and update `.gitignore`

**Files:**
- Create: `.env.example`
- Modify: `.gitignore`

- [ ] **Step 1: Create `.env.example`**

```
# OpenRouter LLM configuration for /assistant build command
# Copy this file to .env and fill in your values.
# Place in the server root (or run/ directory during development).
OPENROUTER_BASE_URL=https://openrouter.ai/api/v1
OPENROUTER_API_KEY=your-api-key-here
OPENROUTER_MODEL=anthropic/claude-sonnet-4
```

- [ ] **Step 2: Add `.env` to `.gitignore`**

Append to the end of `.gitignore`:

```
# Environment variables (secrets)
.env
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add .env.example .gitignore
git commit -m "chore: add .env.example template and gitignore .env files"
```
