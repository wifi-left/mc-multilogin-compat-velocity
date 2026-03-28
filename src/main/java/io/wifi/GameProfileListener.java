package io.wifi;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 监听 {@link GameProfileRequestEvent}，从自定义服务器获取玩家 Profile（含皮肤）。
 *
 * authlib-injector 标准 Profile 接口：
 *   GET {base}/sessionserver/session/minecraft/profile/{uuid}?unsigned=false
 *
 * 响应格式示例：
 * <pre>
 * {
 *   "id": "uuid（无连字符）",
 *   "name": "玩家名",
 *   "properties": [
 *     {
 *       "name": "textures",
 *       "value": "Base64(JSON)",
 *       "signature": "RSA 签名（可选）"
 *     }
 *   ]
 * }
 * </pre>
 */
public class GameProfileListener {

    private static final int     HTTP_TIMEOUT_SEC = 5;
    private static final Gson    GSON             = new Gson();

    private final Logger     logger;
    private final HttpClient httpClient;

    public GameProfileListener(Logger logger) {
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC))
                .build();
    }

    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        // 仅在在线模式下拦截
        if (!event.isOnlineMode()) {
            return;
        }

        String playerName = event.getUsername();
        UUID   uuid       = event.getGameProfile().getId();

        // 将 UUID 转换为无连字符的 32 位十六进制字符串
        String rawUuid = uuid.toString().replace("-", "");

        String profileUrl = CustomAuthPlugin.AUTH_BASE_URL
                + "/sessionserver/session/minecraft/profile/"
                + rawUuid
                + "?unsigned=false";

        logger.debug("[CustomAuth] 正在从自定义服务器获取 Profile: player={}, url={}",
                playerName, profileUrl);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(profileUrl))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();

            if (statusCode == 200 && response.body() != null && !response.body().isEmpty()) {
                GameProfile customProfile = parseProfile(response.body());
                if (customProfile != null) {
                    event.setGameProfile(customProfile);
                    logger.info("[CustomAuth] ✓ 已为玩家 {} 设置自定义 Profile（含皮肤）", playerName);
                } else {
                    logger.warn("[CustomAuth] 解析自定义 Profile 失败，玩家 {} 将使用默认 Profile", playerName);
                }
            } else if (statusCode == 204 || response.body() == null || response.body().isEmpty()) {
                // 204 No Content：玩家不存在于自定义服务器，保留 Velocity 默认处理
                logger.info("[CustomAuth] 自定义服务器未返回玩家 {} 的 Profile（HTTP {}），使用默认", playerName, statusCode);
            } else {
                logger.warn("[CustomAuth] 自定义服务器返回异常状态码 {} for {}", statusCode, playerName);
            }

        } catch (java.net.http.HttpTimeoutException e) {
            logger.warn("[CustomAuth] 请求超时，无法获取玩家 {} 的自定义 Profile", playerName);
        } catch (Exception e) {
            logger.error("[CustomAuth] 获取自定义 Profile 时发生错误，玩家: {}", playerName, e);
        }
    }

    /**
     * 解析 authlib-injector 标准的 Profile JSON，构建 {@link GameProfile}。
     */
    private GameProfile parseProfile(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);

            // 基础字段
            String rawId = obj.get("id").getAsString();
            String name  = obj.get("name").getAsString();

            // 将无连字符 UUID 转换为标准 UUID
            UUID uuid = toUUID(rawId);

            // 解析 properties（主要是 textures 皮肤数据）
            List<GameProfile.Property> properties = new ArrayList<>();
            if (obj.has("properties") && obj.get("properties").isJsonArray()) {
                JsonArray propsArray = obj.getAsJsonArray("properties");
                for (var element : propsArray) {
                    if (!element.isJsonObject()) continue;
                    JsonObject prop = element.getAsJsonObject();

                    String propName  = prop.get("name").getAsString();
                    String propValue = prop.get("value").getAsString();

                    if (prop.has("signature") && !prop.get("signature").isJsonNull()) {
                        String sig = prop.get("signature").getAsString();
                        properties.add(new GameProfile.Property(propName, propValue, sig));
                    } else {
                        properties.add(new GameProfile.Property(propName, propValue, ""));
                    }
                }
            }

            return new GameProfile(uuid, name, properties);

        } catch (Exception e) {
            logger.error("[CustomAuth] Profile JSON 解析失败: {}", e.getMessage());
            logger.debug("[CustomAuth] 原始响应体: {}", json);
            return null;
        }
    }

    /**
     * 将 32 位无连字符十六进制字符串转换为标准 UUID。
     * 例： "7125b8f2a5e94369bc7a3f9c5d82e102"
     *   → "7125b8f2-a5e9-4369-bc7a-3f9c5d82e102"
     */
    private static UUID toUUID(String raw) {
        if (raw.length() != 32) {
            // 已经是带连字符的格式，直接解析
            return UUID.fromString(raw);
        }
        String formatted = raw.substring(0, 8) + "-"
                + raw.substring(8, 12) + "-"
                + raw.substring(12, 16) + "-"
                + raw.substring(16, 20) + "-"
                + raw.substring(20);
        return UUID.fromString(formatted);
    }
}
