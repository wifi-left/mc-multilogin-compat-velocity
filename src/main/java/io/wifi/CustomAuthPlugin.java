package io.wifi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

/**
 * Velocity 自定义认证插件
 *
 * 遵循 authlib-injector 标准，将以下两类请求重定向到自定义服务器：
 * 1. hasJoined 登录校验 → GET {base}/sessionserver/session/minecraft/hasJoined
 * 2. 皮肤/Profile 获取 → GET {base}/sessionserver/session/minecraft/profile/{uuid}
 *
 * 自定义服务器地址：http://127.0.0.1:25600/login_train
 */
@Plugin(id = "multilogin-auth-compat", name = "MultiLogin Service Compat", version = "1.0.0", description = "将 Mojang 认证及皮肤接口重定向到 MC-MultiLogin-service (authlib-injector 标准)", authors = {
        "wifi-left" })
public class CustomAuthPlugin {

    /** 自定义认证服务器基础地址（不含末尾斜线），从配置加载后覆盖此值 */
    public static String AUTH_BASE_URL = "http://127.0.0.1:25600/login_train";
    private final Path dataDirectory;
    private static Gson GSON = new Gson();
    private final ProxyServer server;
    private final Logger logger;
    private LocalAuthProxy localProxy;

    @Inject
    public CustomAuthPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    private ModConfig loadOrCreateConfig(Path path) {
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                ModConfig loaded = GSON.fromJson(json, ModConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException e) {
                logger.warn("[MultiLogin] Failed to read config, using defaults: {}", e.getMessage());
            }
        }

        // Write a default (blank) config so the server operator can fill it in.
        ModConfig defaults = new ModConfig();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(defaults));
            logger.info("[MultiLogin] Default config written to {}", path.toAbsolutePath());
        } catch (IOException e) {
            logger.warn("[MultiLogin] Could not write default config: {}", e.getMessage());
        }
        return defaults;
    }

    public static ModConfig config;

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        Path configPath = dataDirectory.resolve("multilogin-compat-config.json");
        config = loadOrCreateConfig(configPath);
        AUTH_BASE_URL = config.getApiUrl();
        logger.info("========================================");
        logger.info("  MultiLogin Service Compat 正在初始化...");
        logger.info("  MC-MultiLogin-service 地址: {}", AUTH_BASE_URL);
        logger.info("========================================");

        // Step 1: 启动本地认证代理（拦截 hasJoined，追加 detail=true，转换 403 错误为可读踢出消息）
        String hasJoinedBase = AUTH_BASE_URL;
        try {
            localProxy = new LocalAuthProxy(AUTH_BASE_URL, logger);
            localProxy.start();
            hasJoinedBase = localProxy.getLocalBaseUrl();
            logger.info("[✓] 本地认证代理已启动，hasJoined 将经由 {} 转发", hasJoinedBase);
        } catch (IOException e) {
            logger.warn("[!] 本地认证代理启动失败，将直接使用上游服务（不支持 detail 错误信息）: {}", e.getMessage());
        }

        // Step 2: 尝试通过反射将 Velocity 内部的 hasJoined URL 替换为本地代理地址
        boolean urlOverridden = SessionServerUrlOverrider.tryOverride(hasJoinedBase, logger);
        if (urlOverridden) {
            logger.info("[✓] hasJoined URL 劫持成功（反射方式），目标: {}", hasJoinedBase);
        } else {
            logger.warn("[!] hasJoined URL 反射劫持失败，外置登录玩家将无法进入服务器！");
            logger.warn("    解决方案：在启动 Velocity 时添加以下 JVM 参数：");
            logger.warn("    -Dmojang.sessionserver={}/sessionserver/session/minecraft/hasJoined",
                    hasJoinedBase);
            logger.warn("    此参数在 Velocity 读取 InitialLoginSessionHandler 之前生效。");
        }

        // Step 3: 注册事件监听器
        server.getEventManager().register(this, new GameProfileListener(logger));
        server.getEventManager().register(this, new PreLoginListener(logger, config));
        server.getEventManager().register(this, new PostLoginListener(logger));
        logger.info("[✓] 事件监听器已注册（GameProfile 替换 + detail 错误踢出）");

        logger.info("MultiLogin Service Compat 初始化完成！");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (localProxy != null) {
            localProxy.stop();
            logger.info("[MultiLogin] 本地认证代理已停止");
        }
    }
}
