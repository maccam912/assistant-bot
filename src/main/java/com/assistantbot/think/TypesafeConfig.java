package com.assistantbot.think;

import com.assistantbot.llm.EnvLoader;
import java.net.URI;
import java.util.function.Function;

/** Loaded only when think mode starts; errors never prevent the mod from loading. */
public record TypesafeConfig(String apiKey, URI endpoint, String model, long intervalMs,
                             long timeoutMs, long maxAgeMs, double confidence, double threatThreshold,
                             int scanRadius, int maxTargets) {
    public static TypesafeConfig load() { return from(EnvLoader::get); }

    static TypesafeConfig from(Function<String, String> env) {
        String key = value(env, "TYPESAFE_API_KEY", "");
        if (key.isBlank() || key.equals("your-api-key-here")) {
            throw new IllegalArgumentException("Set TYPESAFE_API_KEY in the server environment or .env first.");
        }
        URI endpoint;
        try {
            endpoint = URI.create(value(env, "TYPESAFE_URL", "https://api.typesafe.ai/v1/systemone"));
            if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("TYPESAFE_URL must be a valid HTTPS endpoint.");
        }
        return new TypesafeConfig(key, endpoint, value(env, "TYPESAFE_MODEL", "jev-latest"),
                (long) number(env, "TYPESAFE_INTERVAL_MS", 1000, 250, 60000),
                (long) number(env, "TYPESAFE_TIMEOUT_MS", 3000, 250, 30000),
                (long) number(env, "TYPESAFE_MAX_AGE_MS", 3000, 250, 30000),
                number(env, "TYPESAFE_MIN_CONFIDENCE", 0.55, 0, 1),
                number(env, "TYPESAFE_THREAT_THRESHOLD", 0.65, 0, 1),
                (int) number(env, "TYPESAFE_SCAN_RADIUS", 20, 4, 32),
                (int) number(env, "TYPESAFE_MAX_TARGETS", 12, 1, 32));
    }

    private static String value(Function<String, String> env, String name, String fallback) {
        String value = env.apply(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double number(Function<String, String> env, String name, double fallback, double min, double max) {
        try {
            double result = Double.parseDouble(value(env, name, Double.toString(fallback)));
            if (Double.isFinite(result) && result >= min && result <= max) return result;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ".");
    }

    @Override public String toString() { return "TypesafeConfig[model=" + model + ", intervalMs=" + intervalMs + "]"; }
}
