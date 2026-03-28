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
 * 监听 {@link GameProfileRequestEvent}，确保玩家 GameProfile（含皮肤）来自
 * MC-MultiLogin-service 而非 Mojang。
 *
 * <p>工作流程：
 * <ol>
 *   <li>{@link SessionServerUrlOverrider} 将 Velocity 的 hasJoined 请求重定向到
 *       MC-MultiLogin-service，服务返回正确的 UUID 和皮肤 properties。</li>
 *   <li>Velocity 解析响应并触发本事件，此时 GameProfile 已包含正确数据。</li>
 *   <li>若 GameProfile 已有 textures property（来自 hasJoined 响应），则直接使用，
 *       无需再次请求。</li>
 *   <li>否则（异常情况或 hasJoined 重定向失败），主动请求
 *       {@code GET {base}/sessionserver/session/minecraft/profile/{uuid}?unsigned=false}
 *       补充皮肤数据。</li>
 * </ol>
 */
public class GameProfileListener {

    private static final int HTTP_TIMEOUT_SEC = 5;
    private static final Gson GSON = new Gson();

    private final Logger logger;
    private final HttpClient httpClient;

    public GameProfileListener(Logger logger) {
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC))
                .build();
    }

    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        // 仅在在线模式下处理
        if (!event.isOnlineMode()) {
            return;
        }

        GameProfile existing = event.getGameProfile();
        String playerName = event.getUsername();

        // 若 hasJoined 重定向已成功，profile 里应当已有 textures property
        if (hasTextures(existing)) {
            logger.debug("[MultiLogin] 玩家 {} 的 Profile 已含 textures，直接使用（来自 hasJoined）", playerName);
            return;
        }

        // 若 profile 含有错误标记属性，说明是 LocalAuthProxy 注入的假 Profile；
        // 跳过远程 Profile 获取——PostLoginListener 会负责踢出玩家并展示错误信息。
        if (hasErrorMarker(existing)) {
            logger.debug("[MultiLogin] 玩家 {} 的 Profile 含错误标记，跳过 Profile 获取", playerName);
            return;
        }

        // 未检测到 textures：可能是 hasJoined 重定向未生效，或上游服务未返回 properties
        // 主动请求 profile 接口，补充皮肤数据
        String rawUuid = existing.getId().toString().replace("-", "");
        String profileUrl = CustomAuthPlugin.AUTH_BASE_URL
                + "/sessionserver/session/minecraft/profile/"
                + rawUuid
                + "?unsigned=false";

        logger.debug("[MultiLogin] 玩家 {} 缺少 textures，正在请求 Profile 接口: {}", playerName, profileUrl);

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
                    logger.info("[MultiLogin] ✓ 已为玩家 {} 设置来自服务的 Profile（含皮肤）", playerName);
                } else {
                    logger.warn("[MultiLogin] 解析 Profile 响应失败，玩家 {} 将使用当前 Profile", playerName);
                }
            } else if (statusCode == 204 || response.body() == null || response.body().isEmpty()) {
                logger.debug("[MultiLogin] 服务未返回玩家 {} 的 Profile（HTTP {}），保留现有 Profile",
                        playerName, statusCode);
            } else {
                logger.warn("[MultiLogin] 服务返回异常状态码 {} for {}", statusCode, playerName);
            }

        } catch (java.net.http.HttpTimeoutException e) {
            logger.warn("[MultiLogin] 请求超时，无法获取玩家 {} 的 Profile", playerName);
        } catch (Exception e) {
            logger.error("[MultiLogin] 获取 Profile 时发生错误，玩家: {}", playerName, e);
        }
    }

    /** 检查 GameProfile 是否已包含 textures property。 */
    private boolean hasTextures(GameProfile profile) {
        for (GameProfile.Property prop : profile.getProperties()) {
            if ("textures".equals(prop.getName())
                    && prop.getValue() != null && !prop.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 检查 GameProfile 是否包含由 {@link LocalAuthProxy} 注入的错误标记属性。 */
    private boolean hasErrorMarker(GameProfile profile) {
        for (GameProfile.Property prop : profile.getProperties()) {
            if (LocalAuthProxy.ERROR_PROPERTY_NAME.equals(prop.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 authlib-injector 标准的 Profile JSON，构建 {@link GameProfile}。
     */
    private GameProfile parseProfile(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);

            String rawId = obj.get("id").getAsString();
            String name = obj.get("name").getAsString();
            UUID uuid = toUUID(rawId);

            List<GameProfile.Property> properties = new ArrayList<>();
            if (obj.has("properties") && obj.get("properties").isJsonArray()) {
                JsonArray propsArray = obj.getAsJsonArray("properties");
                for (var element : propsArray) {
                    if (!element.isJsonObject()) continue;
                    JsonObject prop = element.getAsJsonObject();

                    String propName = prop.get("name").getAsString();
                    String propValue = prop.get("value").getAsString();
                    String sig = (prop.has("signature") && !prop.get("signature").isJsonNull())
                            ? prop.get("signature").getAsString() : "";
                    properties.add(new GameProfile.Property(propName, propValue, sig));
                }
            }

            return new GameProfile(uuid, name, properties);

        } catch (Exception e) {
            logger.error("[MultiLogin] Profile JSON 解析失败: {}", e.getMessage());
            logger.debug("[MultiLogin] 原始响应体: {}", json);
            return null;
        }
    }

    /**
     * 将 32 位无连字符十六进制字符串转换为标准 UUID。
     */
    private static UUID toUUID(String raw) {
        if (raw.length() != 32) {
            return UUID.fromString(raw);
        }
        return UUID.fromString(
                raw.substring(0, 8) + "-"
                + raw.substring(8, 12) + "-"
                + raw.substring(12, 16) + "-"
                + raw.substring(16, 20) + "-"
                + raw.substring(20));
    }
}

