package io.wifi;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.wifi.api.ErrorResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 本地 HTTP 代理，拦截 Velocity 对 hasJoined 接口的请求。
 *
 * <p>工作流程：
 * <ol>
 *   <li>Velocity 向本代理发起 hasJoined 请求（URL 由 {@link SessionServerUrlOverrider} 重定向至此）。</li>
 *   <li>本代理将请求追加 {@code &detail=true} 后转发给真实的 MC-MultiLogin-service。</li>
 *   <li>若服务返回 HTTP 200，直接透传 GameProfile JSON 给 Velocity。</li>
 *   <li>若服务返回 HTTP 403（detail 错误），解析错误体，将错误信息存入 {@link #pendingKicks}，
 *       并向 Velocity 返回一个带有错误标记属性的"假成功"Profile，使 Velocity 认为认证通过。</li>
 *   <li>随后 {@link PostLoginListener} 在 PostLoginEvent 中检测到该玩家存在待踢出记录，
 *       调用 {@code player.disconnect(errorMessage)} 向玩家展示可读的错误信息。</li>
 *   <li>其他状态码（如 204）直接透传，Velocity 会按标准流程踢出玩家。</li>
 * </ol>
 */
public class LocalAuthProxy {

    /** 注入到 GameProfile properties 中的错误标记属性名。 */
    public static final String ERROR_PROPERTY_NAME = "__ml_error__";

    /**
     * 待踢出玩家表，key 为小写用户名，value 为给玩家显示的错误消息。
     * 由 {@link PostLoginListener} 消费并清除。
     */
    public static final ConcurrentHashMap<String, String> pendingKicks = new ConcurrentHashMap<>();

    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String upstreamBase;
    private final Logger logger;
    private final HttpServer httpServer;
    private final HttpClient httpClient;
    private final java.util.concurrent.ExecutorService executor;
    private final int port;

    public LocalAuthProxy(String upstreamBase, Logger logger) throws IOException {
        this.upstreamBase = upstreamBase.endsWith("/")
                ? upstreamBase.substring(0, upstreamBase.length() - 1)
                : upstreamBase;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();

        // 自动选取本地可用端口
        try (ServerSocket tmp = new ServerSocket(0)) {
            this.port = tmp.getLocalPort();
        }

        this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        this.httpServer.createContext("/", this::handleRequest);
        this.executor = Executors.newFixedThreadPool(4);
        this.httpServer.setExecutor(this.executor);
    }

    /** 启动本地代理服务器。 */
    public void start() {
        httpServer.start();
        logger.info("[MultiLogin] 本地认证代理已启动，监听端口 {}", port);
    }

    /** 停止本地代理服务器并关闭线程池。 */
    public void stop() {
        httpServer.stop(1);
        executor.shutdown();
    }

    /** 返回本代理的基础 URL，格式为 {@code http://127.0.0.1:<port>}。 */
    public String getLocalBaseUrl() {
        return "http://127.0.0.1:" + port;
    }

    // ── 请求处理 ────────────────────────────────────────────────────────────

    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String rawQuery = exchange.getRequestURI().getRawQuery();

        // 拼接上游 URL 并追加 detail=true
        String upstreamUrl = upstreamBase + path
                + "?" + (rawQuery != null ? rawQuery + "&detail=true" : "detail=true");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(upstreamUrl))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body() != null ? response.body() : "";

            if (status == 200 && !body.isEmpty()) {
                // 认证成功，透传给 Velocity
                sendJson(exchange, 200, body);

            } else if (status == 200) {
                // 上游返回 200 但响应体为空，异常情况，透传 204 触发标准失败流程
                logger.warn("[MultiLogin] 上游服务返回 HTTP 200 但响应体为空，将按认证失败处理");
                sendEmpty(exchange, 204);

            } else if (status == 403 && !body.isEmpty()) {
                // 服务返回了 detail 错误，解析并拦截
                handleDetailError(exchange, rawQuery, body);

            } else {
                // 204 或其他状态码，透传（Velocity 会按标准流程处理失败）
                sendEmpty(exchange, status);
            }

        } catch (Exception e) {
            logger.warn("[MultiLogin] 本地代理请求上游失败: {}", e.getMessage());
            sendEmpty(exchange, 204);
        }
    }

    /**
     * 处理上游返回的 HTTP 403 detail 错误：
     * 将错误消息写入 {@link #pendingKicks}，并向 Velocity 返回一个带错误标记的假 Profile，
     * 使 Velocity 继续走登录流程（随后 PostLoginListener 再踢出玩家并展示错误信息）。
     */
    private void handleDetailError(HttpExchange exchange, String rawQuery, String body) throws IOException {
        ErrorResponse err = GSON.fromJson(body, ErrorResponse.class);
        String username = extractUsername(rawQuery);

        if (err == null || username == null) {
            // 无法解析错误或用户名，降级为 204
            logger.debug("[MultiLogin] 无法解析 403 响应体或提取用户名，降级为 204；原始响应: {}", body);
            sendEmpty(exchange, 204);
            return;
        }

        String errorMessage = buildErrorMessage(err);
        pendingKicks.put(username.toLowerCase(), errorMessage);
        logger.debug("[MultiLogin] 拦截玩家 {} 的登录失败，原因: {} ({})", username, err.getCause(), errorMessage);

        String fakeProfile = buildFakeProfile(username, body);
        sendJson(exchange, 200, fakeProfile);
    }

    /** 根据 {@link ErrorResponse} 组装用户可读的错误消息。 */
    private static String buildErrorMessage(ErrorResponse err) {
        String msg = err.getErrorMessage() != null && !err.getErrorMessage().isEmpty()
                ? err.getErrorMessage()
                : "登录失败";

        // 如果有可用的替代名，附加提示
        if (err.isDuplicateName() && err.getAvailableId() != null && !err.getAvailableId().isEmpty()) {
            msg += "（可尝试使用账号名 " + err.getAvailableId() + " 登录）";
        }
        return msg;
    }

    /**
     * 构造一个带有错误标记属性的假 GameProfile JSON。
     *
     * <p>UUID 由用户名确定性生成（避免全零 UUID），确保不与真实玩家冲突。
     * properties 中包含一条名为 {@value #ERROR_PROPERTY_NAME} 的属性，
     * value 为 Base64 编码的原始错误 JSON，供 {@link GameProfileListener} 识别。
     */
    private static String buildFakeProfile(String username, String errorJson) {
        UUID fakeUuid = UUID.nameUUIDFromBytes(
                ("multilogin_error:" + username).getBytes(StandardCharsets.UTF_8));

        String encodedError = Base64.getEncoder()
                .encodeToString(errorJson.getBytes(StandardCharsets.UTF_8));

        JsonObject errorProp = new JsonObject();
        errorProp.addProperty("name", ERROR_PROPERTY_NAME);
        errorProp.addProperty("value", encodedError);
        errorProp.addProperty("signature", "");

        JsonArray properties = new JsonArray();
        properties.add(errorProp);

        JsonObject profile = new JsonObject();
        profile.addProperty("id", fakeUuid.toString().replace("-", ""));
        profile.addProperty("name", username);
        profile.add("properties", properties);

        return GSON.toJson(profile);
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    /** 从 URL 查询字符串中提取 {@code username} 参数值。 */
    private static String extractUsername(String rawQuery) {
        if (rawQuery == null) return null;
        for (String param : rawQuery.split("&")) {
            if (param.startsWith("username=")) {
                try {
                    return URLDecoder.decode(param.substring(9), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return param.substring(9);
                }
            }
        }
        return null;
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.getResponseBody().close();
    }
}
