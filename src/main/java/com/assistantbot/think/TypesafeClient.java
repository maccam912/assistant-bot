package com.assistantbot.think;

import com.google.gson.JsonParser;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class TypesafeClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final TypesafeConfig config;

    public TypesafeClient(TypesafeConfig config) { this.config = config; }

    public CompletableFuture<ThinkProtocol.Evaluation> evaluate(ThinkProtocol.Request query) {
        HttpRequest request = HttpRequest.newBuilder(config.endpoint())
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query.body().toString())).build();
        var transport = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var result = new CompletableFuture<ThinkProtocol.Evaluation>();
        transport.whenComplete((response, error) -> {
            if (error != null) { result.completeExceptionally(error); return; }
            try {
                if (response.statusCode() != 200) {
                    long retryMs = response.headers().firstValue("Retry-After").map(TypesafeClient::retryMillis).orElse(0L);
                    throw new ApiException(response.statusCode(), retryMs);
                }
                result.complete(ThinkProtocol.parse(JsonParser.parseString(response.body()).getAsJsonObject(), query));
            } catch (RuntimeException e) { result.completeExceptionally(e); }
        });
        result.whenComplete((ignored, error) -> { if (result.isCancelled()) transport.cancel(true); });
        return result;
    }

    private static long retryMillis(String value) {
        try { return Math.min(300000, Math.max(0, Math.multiplyExact(Long.parseLong(value), 1000))); }
        catch (ArithmeticException | NumberFormatException e) { return 0; }
    }

    public static final class ApiException extends RuntimeException {
        private final int status;
        private final long retryMs;
        public ApiException(int status, long retryMs) {
            super("TypeSafe HTTP " + status);
            this.status = status;
            this.retryMs = retryMs;
        }
        public boolean permanent() { return status >= 400 && status < 500 && status != 408 && status != 429; }
        public long retryMs() { return retryMs; }
    }
}
