package io.wifi;

import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 通过反射将 Velocity 内部硬编码的 Mojang sessionserver URL 替换为自定义地址。
 *
 * Velocity 3.x 的 hasJoined 校验地址存放在：
 *   com.velocitypowered.proxy.connection.client.InitialLoginSessionHandler
 * 的静态字段 MOJANG_HASJOINED_URL 中，格式为：
 *   "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s"
 *
 * Velocity 在构造请求时会调用 String.format(MOJANG_HASJOINED_URL, username, serverId)，
 * 所以替换值也必须保留 %s 占位符。
 *
 * 也支持通过 JVM 启动参数设置（无需本插件运行）：
 *   -Dmojang.sessionserver=http://your-service/path/sessionserver/session/minecraft/hasJoined
 *
 * 采用两级回退策略：
 * 1. 普通反射 setAccessible（非 final 字段或 Java < 12 时有效）
 * 2. sun.misc.Unsafe 直接写入静态字段内存（Java 9+ 兜底）
 */
public final class SessionServerUrlOverrider {

    /**
     * Velocity 中可能存放 hasJoined URL 的候选类，按优先级排列。
     * InitialLoginSessionHandler 是 Velocity 3.x 的实际位置。
     */
    private static final String[] CANDIDATE_CLASSES = {
            "com.velocitypowered.proxy.connection.client.InitialLoginSessionHandler",
            "com.velocitypowered.proxy.connection.client.LoginSessionHandler",
            "com.velocitypowered.proxy.util.VelocityAuthUtils",
            "com.velocitypowered.proxy.util.AuthUtils",
            "com.velocitypowered.proxy.VelocityServer"
    };

    /** 候选字段名（不同版本命名不同） */
    private static final String[] CANDIDATE_FIELDS = {
            "MOJANG_HASJOINED_URL",
            "HAS_JOINED_URL",
            "AUTH_URL",
            "SESSION_SERVER_URL",
            "MOJANG_HASJOIN_URL"
    };

    /** Mojang 官方 hasJoined 地址关键字，用于判断字段值是否为目标 */
    private static final String MOJANG_SESSION_HOST = "sessionserver.mojang.com";

    private SessionServerUrlOverrider() {
    }

    /**
     * 尝试将 hasJoined URL 替换为自定义服务器。
     *
     * <p>替换值保留 Velocity 使用的 {@code ?username=%s&serverId=%s} 格式占位符，
     * 因为 Velocity 会通过 {@code String.format(MOJANG_HASJOINED_URL, username, serverId)}
     * 来构造最终 URL。
     *
     * @param authBaseUrl 自定义认证服务器基础 URL（不含末尾斜线），例如
     *                    {@code http://127.0.0.1:25600/login_train}
     * @param logger      日志对象
     * @return 是否成功替换
     */
    public static boolean tryOverride(String authBaseUrl, Logger logger) {
        // 替换值必须保留 ?username=%s&serverId=%s，因为 Velocity 会用 String.format 填充它们
        String targetUrl = authBaseUrl
                + "/sessionserver/session/minecraft/hasJoined?username=%s&serverId=%s";

        // ── 扫描候选类字段，依次尝试反射/Unsafe 写入 ─────────────────────
        for (String className : CANDIDATE_CLASSES) {
            Class<?> clazz;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                continue; // 该类不存在，跳过
            }

            for (String fieldName : CANDIDATE_FIELDS) {
                Field field;
                try {
                    field = clazz.getDeclaredField(fieldName);
                } catch (NoSuchFieldException e) {
                    continue;
                }

                // 确认字段类型为 String
                if (!String.class.equals(field.getType()))
                    continue;

                try {
                    field.setAccessible(true);
                    Object currentValue = field.get(null);
                    // 仅替换包含 mojang sessionserver 的字段
                    if (!(currentValue instanceof String s) || !s.contains(MOJANG_SESSION_HOST)) {
                        continue;
                    }

                    boolean written = writeField(field, targetUrl, logger);
                    if (written) {
                        logger.info("已替换字段 {}.{}", className, fieldName);
                        logger.info("  原值: {}", currentValue);
                        logger.info("  新值: {}", targetUrl);
                        return true;
                    }
                } catch (IllegalAccessException e) {
                    logger.debug("无法读取字段 {}.{}: {}", className, fieldName, e.getMessage());
                }
            }
        }

        return false;
    }

    // ── 写入策略 1：标准反射（清除 final 修饰符，Java < 12 时有效）────────
    private static boolean writeViaReflection(Field field, String value) {
        try {
            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            field.set(null, value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── 写入策略 2：sun.misc.Unsafe（Java 9+ 兜底）───────────────────────
    // Unsafe 是内部 API，在 Java 17+ 上 static final 字段无法通过普通反射修改，
    // 因此必须借助 Unsafe.putObject 直接写入内存。
    // 若未来 Unsafe 被移除，需改为 Java Agent / ASM 字节码注入方式。
    @SuppressWarnings("removal")
    private static boolean writeViaUnsafe(Field field, String value, Logger logger) {
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) theUnsafeField.get(null);

            Object base = unsafe.staticFieldBase(field);
            long offset = unsafe.staticFieldOffset(field);
            unsafe.putObject(base, offset, value);
            return true;
        } catch (Exception e) {
            logger.debug("Unsafe 写入失败: {}", e.getMessage());
            return false;
        }
    }

    private static boolean writeField(Field field, String value, Logger logger) {
        // 先尝试普通反射（对非 final 字段或低版本 JVM 有效）
        if (!Modifier.isFinal(field.getModifiers())) {
            try {
                field.set(null, value);
                return true;
            } catch (IllegalAccessException ignored) {
            }
        }
        if (writeViaReflection(field, value))
            return true;
        // 兜底使用 Unsafe（在 Java 17 上通常可行）
        return writeViaUnsafe(field, value, logger);
    }
}
