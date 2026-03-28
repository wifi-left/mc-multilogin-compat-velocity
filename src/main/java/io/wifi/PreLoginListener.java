package io.wifi;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;

import io.wifi.api.LoginApiClient;

import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class PreLoginListener {
    private ModConfig config ;
    private static final int HTTP_TIMEOUT_SEC = 5;
    private static final Gson GSON = new Gson();

    private final Logger logger;
    private final HttpClient httpClient;
    public static final HashMap<UUID, GameProfile> LoginInfos = new HashMap<>();
    private final LoginApiClient apiClient;

    public PreLoginListener(Logger logger, ModConfig config) {
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC))
                .build();
        this.config = config;
        this.apiClient = new LoginApiClient(config.getApiUrl());
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        // String username = event.getUsername();
        // InetAddress ipa = null;
        // if(config.getForceNoProxy()){
        //     ipa = event.getConnection().getRemoteAddress().getAddress();
        // }
        // 这里处理不了登录，没有给玩家发要求登陆的包
    }

    /**
     * 解析 authlib-injector 标准的 Profile JSON，构建 {@link GameProfile}。
     */
    private GameProfile parseProfile(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);

            // 基础字段
            String rawId = obj.get("id").getAsString();
            String name = obj.get("name").getAsString();

            // 将无连字符 UUID 转换为标准 UUID
            UUID uuid = toUUID(rawId);

            // 解析 properties（主要是 textures 皮肤数据）
            List<GameProfile.Property> properties = new ArrayList<>();
            if (obj.has("properties") && obj.get("properties").isJsonArray()) {
                JsonArray propsArray = obj.getAsJsonArray("properties");
                for (var element : propsArray) {
                    if (!element.isJsonObject())
                        continue;
                    JsonObject prop = element.getAsJsonObject();

                    String propName = prop.get("name").getAsString();
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
     * → "7125b8f2-a5e9-4369-bc7a-3f9c5d82e102"
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
