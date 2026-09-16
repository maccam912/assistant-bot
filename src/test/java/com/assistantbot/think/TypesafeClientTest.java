package com.assistantbot.think;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TypesafeClientTest {
    @Test void sendsAuthenticatedStructuredRequestAndReadsTypedAnswers() throws Exception {
        var body = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var query = ThinkFixtures.request();
        server.createContext("/v1/systemone", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            byte[] response = ThinkFixtures.response(query, "PROTECT", "ATTACK", "zombie").toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var evaluation = new TypesafeClient(localConfig(server)).evaluate(query).get(5, TimeUnit.SECONDS);
            assertEquals("PROTECT", evaluation.goal().value());
            assertEquals("POST", method.get());
            assertEquals("Bearer test-key", authorization.get());
            assertEquals(query.body(), JsonParser.parseString(body.get()));
        } finally { server.stop(0); }
    }

    @Test void reportsStatusAndRetryAfterWithoutExposingErrorBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "private diagnostic body".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Retry-After", "7");
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var future = new TypesafeClient(localConfig(server)).evaluate(ThinkFixtures.request());
            var error = assertThrows(CompletionException.class, () -> future.orTimeout(5, TimeUnit.SECONDS).join());
            var api = assertInstanceOf(TypesafeClient.ApiException.class, error.getCause());
            assertEquals(7000, api.retryMs());
            assertFalse(api.permanent());
            assertFalse(api.getMessage().contains("private"));
        } finally { server.stop(0); }
    }

    private TypesafeConfig localConfig(HttpServer server) {
        var config = ThinkFixtures.config();
        return new TypesafeConfig(config.apiKey(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone"),
                config.model(), config.intervalMs(), config.timeoutMs(), config.maxAgeMs(), config.confidence(),
                config.threatThreshold(), config.scanRadius(), config.maxTargets());
    }
}
