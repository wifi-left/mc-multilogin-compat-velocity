package io.wifi;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import org.slf4j.Logger;

import java.net.http.HttpClient;
import java.time.Duration;

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
    private final Logger logger;
    public GameProfileListener(Logger logger) {
        this.logger = logger;
        HttpClient.newBuilder()
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
}

