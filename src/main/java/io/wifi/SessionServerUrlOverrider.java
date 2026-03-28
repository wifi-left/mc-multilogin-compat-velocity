package io.wifi;

import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 通过反射将 Velocity 内部硬编码的 Mojang sessionserver URL 替换为自定义地址。
 *
 * Velocity 的 hasJoined 校验地址存放在：
 * com.velocitypowered.proxy.connection.client.LoginSessionHandler
 * 的静态字符串字段中。
 *
 * 采用三级回退策略：
 * 1. JVM 系统属性 velocity.mojangSessionServerUrl（部分版本支持）
 * 2. 普通反射 setAccessible + 清除 FINAL 修饰符
 * 3. sun.misc.Unsafe 直接写入静态字段内存（兜底方案）
 */
public final class SessionServerUrlOverrider {

    /** Velocity 中可能存放 hasJoined URL 的候选类 */
    private static final String[] CANDIDATE_CLASSES = {
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

    /** Mojang 官方 hasJoined 地址，用于判断字段值是否为目标 */
    private static final String MOJANG_SESSION_HOST = "sessionserver.mojang.com";

    private SessionServerUrlOverrider() {
    }

    /**
     * 尝试将 hasJoined URL 替换为自定义服务器。
     *
     * @param authBaseUrl 自定义认证服务器基础 URL（不含末尾斜线）
     * @param logger      日志对象
     * @return 是否成功替换
     */
    public static boolean tryOverride(String authBaseUrl, Logger logger) {
        String targetUrl = authBaseUrl + "/sessionserver/session/minecraft/hasJoined";

        // ── 策略 1：JVM 系统属性（Velocity 部分版本支持）──────────────────
        String sysProp = System.getProperty("velocity.mojangSessionServerUrl");
        if (sysProp != null && sysProp.contains(MOJANG_SESSION_HOST)) {
            System.setProperty("velocity.mojangSessionServerUrl", targetUrl);
            logger.info("已通过 JVM 系统属性覆盖 hasJoined URL");
            return true;
        }
        // 预防性地设置（如果 Velocity 稍后才读取该属性）
        System.setProperty("velocity.mojangSessionServerUrl", targetUrl);

        // ── 策略 2/3：扫描候选类字段，依次尝试反射/Unsafe 写入 ───────────
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

    // ── 写入策略 2：标准反射（清除 final 修饰符）──────────────────────────
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

    // ── 写入策略 3：sun.misc.Unsafe（Java 9+ 兜底）───────────────────────
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
        // 先尝试普通反射
        if (!Modifier.isFinal(field.getModifiers())) {
            try {
                field.set(null, value);
                return true;
            } catch (IllegalAccessException ignored) {
            }
        }
        if (writeViaReflection(field, value))
            return true;
        // 兜底使用 Unsafe
        return writeViaUnsafe(field, value, logger);
    }
}
