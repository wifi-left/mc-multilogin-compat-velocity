package io.wifi;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * 监听 {@link GameProfileRequestEvent}，从自定义服务器获取玩家 Profile（含皮肤）。
 *
 * authlib-injector 标准 Profile 接口：
 * GET {base}/sessionserver/session/minecraft/profile/{uuid}?unsigned=false
 *
 * 响应格式示例：
 * 
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

    public final Logger logger;

    public GameProfileListener(Logger logger) {
        this.logger = logger;
    }

    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        // 仅在在线模式下拦截
        if (!event.isOnlineMode()) {
            return;
        }
        UUID uuid = event.getGameProfile().getId();
        if (!PreLoginListener.LoginInfos.containsKey(uuid)) {
            return;
        }
        event.setGameProfile(PreLoginListener.LoginInfos.remove(uuid));
    }
}
