package com.qwerlty.myojcodesandbox.security;

import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;

import java.nio.charset.StandardCharsets;

/**
 * 与后端一致的 API 签名校验工具
 * 使用 HMAC-SHA256 验证 timestamp + body 的签名
 */
public final class ApiSignUtil {

    private static final String SEP = "\n";

    /**
     * 生成签名（与后端算法一致，用于服务端校验）
     */
    public static String sign(String secretKey, long timestamp, String body) {
        String payload = timestamp + SEP + (body != null ? body : "");
        HMac hmac = new HMac(HmacAlgorithm.HmacSHA256, secretKey.getBytes(StandardCharsets.UTF_8));
        byte[] digest = hmac.digest(payload.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(digest);
    }

    /**
     * 验证签名
     */
    public static boolean verify(String secretKey, long timestamp, String body, String signature) {
        if (secretKey == null || signature == null || signature.isEmpty()) {
            return false;
        }
        String expected = sign(secretKey, timestamp, body);
        return expected.equalsIgnoreCase(signature.trim());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private ApiSignUtil() {
    }
}
