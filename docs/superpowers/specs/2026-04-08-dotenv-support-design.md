# .env File Support for LLM Configuration

## Problem

The mod requires three environment variables (`OPENROUTER_BASE_URL`, `OPENROUTER_API_KEY`,
`OPENROUTER_MODEL`) for LLM integration. Currently these must be set at the OS/shell level,
which is inconvenient for development and easy to forget. There is also no `.gitignore` entry
for `.env`, risking accidental secret commits.

## Decision

Hand-rolled `.env` parser. No new dependencies. The requirements are trivial (3 flat
`KEY=VALUE` entries) and don't justify adding a library.

## Design

### New class: `EnvLoader`

**Package:** `com.assistantbot.llm`  
**Purpose:** Provide a `get(name)` method that checks `System.getenv()` first, then falls back
to values parsed from a `.env` file.

**Behavior:**

- `static String get(String name)` — returns the OS environment variable if set and non-blank;
  otherwise returns the `.env` value (or null if neither exists).
- On first call, reads `.env` from the current working directory (`run/` during dev, server
  root in production). Parses into a `Map<String, String>` and caches it for all subsequent
  calls.
- **Thread-safe** via `synchronized` on the load path (runs once).
- If `.env` does not exist, the fallback map is empty — no error. Real env vars still work.

**Parser rules:**

- Skip blank lines and lines starting with `#`
- Strip optional `export ` prefix
- Split on the first `=` only (values may contain `=`)
- Strip surrounding single or double quotes from values
- Trim whitespace from keys and values

**Intentionally not supported:**

- Variable interpolation (`${FOO}`)
- Multiline values
- Escape sequences beyond simple quoting

These are unnecessary for 3 flat config values.

### Changes to `LlmClient`

In `requireEnv(String name)` (line 188), replace:

```java
String value = System.getenv(name);
```

with:

```java
String value = EnvLoader.get(name);
```

No other changes to `LlmClient`.

### New file: `.env.example`

```
# OpenRouter LLM configuration for /assistant build command
OPENROUTER_BASE_URL=https://openrouter.ai/api/v1
OPENROUTER_API_KEY=your-api-key-here
OPENROUTER_MODEL=anthropic/claude-sonnet-4
```

### `.gitignore` addition

Add `.env` to the existing `.gitignore` to prevent accidental commits of secrets.

## Loading strategy

**Lazy, on first LLM call.** The `.env` file is only read when `EnvLoader.get()` is first
invoked, which happens inside `LlmClient.requestStructure()`. The mod loads and works
normally without a `.env` file as long as LLM features are not used.

## File placement

The `.env` file lives in the working directory:

- **Development:** `run/` (Gradle's `runServer`/`runClient` sets CWD to `run/`)
- **Production:** The server root (wherever `java -jar server.jar` is executed)

This matches the standard convention for dotenv files.
