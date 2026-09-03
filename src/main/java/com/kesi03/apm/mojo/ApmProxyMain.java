package com.kesi03.apm.mojo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ApmProxyMain {
    private ApmProxyMain() {
    }

    public static void main(String[] args) throws Exception {
        ProxyConfig config = ProxyConfig.from(args);
        Files.createDirectories(config.saveDir());
        HttpServer server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        ProxyHandler handler = new ProxyHandler(config);
        server.createContext("/intake/v2/events", exchange -> handler.handle(exchange, "events"));
        server.createContext("/intake/v2/rum/events", exchange -> handler.handle(exchange, "rum"));
        server.createContext("/v1", exchange -> handler.handle(exchange, "otlp"));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf("[APM Proxy] Listening on http://%s:%d%n", config.host(), config.port());
    }

    record ProxyConfig(
            String host, int port, String apmServer, String apmToken, String otlpServer,
            boolean forward, boolean save, Path saveDir, String logPrefix, boolean printPayloads) {
        static ProxyConfig from(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("host", value(args, "--host", env("APM_HOST", "0.0.0.0")));
            values.put("port", value(args, "--port", env("APM_PORT", "8200")));
            values.put("apmServer", env("APM_SERVER", ""));
            values.put("apmToken", env("APM_TOKEN", ""));
            values.put("otlpServer", env("OTLP_SERVER", ""));
            values.put("forward", env("APM_FORWARD", "false"));
            values.put("save", env("APM_SAVE", "true"));
            values.put("saveDir", env("APM_SAVE_DIR", "./apm_logs"));
            values.put("logPrefix", env("APM_LOG_PREFIX", "events"));
            values.put("print", env("APM_PRINT", "false"));
            return new ProxyConfig(
                    values.get("host"),
                    Integer.parseInt(values.get("port")),
                    values.get("apmServer"),
                    values.get("apmToken"),
                    values.get("otlpServer"),
                    Boolean.parseBoolean(values.get("forward")),
                    Boolean.parseBoolean(values.get("save")),
                    Path.of(values.get("saveDir")).toAbsolutePath().normalize(),
                    values.get("logPrefix"),
                    Boolean.parseBoolean(values.get("print")));
        }

        private static String value(String[] args, String name, String fallback) {
            for (int i = 0; i < args.length - 1; i++) {
                if (name.equals(args[i])) return args[i + 1];
            }
            return fallback;
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    static final class ProxyHandler {
        private final ProxyConfig config;
        private final HttpClient client = HttpClient.newHttpClient();

        ProxyHandler(ProxyConfig config) {
            this.config = config;
        }

        void handle(HttpExchange exchange, String kind) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            String suffix = kind.equals("otlp") ? path.substring("/v1/".length()) : kind;
            String diagnostic = diagnostic(exchange, kind, body);
            if (config.printPayloads()) System.out.println(diagnostic);
            if (config.save()) {
                Path file = config.saveDir().resolve(config.logPrefix() + "_" + suffix + (kind.equals("otlp") ? ".otlp" : ".ndjson"));
                Files.writeString(file, "# ---- Received: " + Instant.now() + " ----\n" + diagnostic + "\n\n",
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }

            if (config.forward() && !config.apmServer().isBlank() && !kind.equals("otlp")) {
                forward(exchange, config.apmServer() + path, body);
            } else if (config.forward() && !config.otlpServer().isBlank() && kind.equals("otlp")) {
                forward(exchange, config.otlpServer() + path, body);
            } else {
                respond(exchange, 200, "{\"accepted\":1}", "application/json");
            }
        }

        private void forward(HttpExchange exchange, String target, byte[] body) throws IOException {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target))
                    .header("Content-Type", exchange.getRequestHeaders().getFirst("Content-Type") == null
                            ? "application/x-ndjson" : exchange.getRequestHeaders().getFirst("Content-Type"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (!config.apmToken().isBlank()) request.header("Authorization", "ApiKey " + config.apmToken());
            try {
                HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                respond(exchange, response.statusCode(), response.body(),
                        response.headers().firstValue("Content-Type").orElse("application/json"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Forwarding request interrupted", e);
            }
        }

        private static String diagnostic(HttpExchange exchange, String kind, byte[] body) {
            StringBuilder result = new StringBuilder("--- Incoming " + kind.toUpperCase() + " ---\n");
            exchange.getRequestHeaders().forEach((key, values) -> result.append(key).append(": ")
                    .append(String.join(",", values)).append('\n'));
            result.append("\n--- Payload ---\n").append(new String(body, StandardCharsets.UTF_8));
            return result.toString();
        }

        private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
            respond(exchange, status, body.getBytes(StandardCharsets.UTF_8), contentType);
        }

        private static void respond(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }
}
