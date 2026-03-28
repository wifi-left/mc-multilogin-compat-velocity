package io.wifi;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PostLoginEvent;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * 监听 {@link PostLoginEvent}，将认证阶段被 {@link LocalAuthProxy} 标记为待踢出的玩家
 * 以具体错误信息断开连接。
 *
 * <p>流程：
 * <ol>
 *   <li>{@link LocalAuthProxy} 在 MC-MultiLogin-service 返回 HTTP 403 时，
 *       向 {@link LocalAuthProxy#pendingKicks} 写入 {@code 用户名 → 错误消息}，
 *       并向 Velocity 返回一个假的成功 Profile 使玩家进入 PostLogin 阶段。</li>
 *   <li>本监听器检测到待踢出记录后，立即调用 {@code player.disconnect()} 并附上
 *       服务端返回的可读错误信息。</li>
 * </ol>
 */
public class PostLoginListener {

    private final Logger logger;

    public PostLoginListener(Logger logger) {
        this.logger = logger;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        String key = event.getPlayer().getUsername().toLowerCase();
        String errorMessage = LocalAuthProxy.pendingKicks.remove(key);
        if (errorMessage != null) {
            event.getPlayer().disconnect(Component.text(errorMessage));
            logger.info("[MultiLogin] 已踢出玩家 {}，原因: {}",
                    event.getPlayer().getUsername(), errorMessage);
        }
    }
}
