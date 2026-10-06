package com.github.feng111627.runtimefeatureflagsjava.flags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 稳定分桶器：对 "开关名:标识" 做 SHA-256 哈希，取前 8 字节映射到 0-99。
 * 同一个 (开关名, 标识) 在规则不变时永远落入同一个桶，判定结果可复现。
 */
public final class Bucketer {

    private static final int BUCKET_COUNT = 100;

    private Bucketer() {
    }

    /**
     * @return 0-99 的桶号
     */
    public static int bucket(String flagName, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            // 无稳定标识时不参与灰度，固定落入最后一个桶（>= 任何小于 100 的比例时按未命中处理之外的语义由调用方决定）
            return BUCKET_COUNT - 1;
        }
        byte[] digest = sha256((flagName + ":" + identifier).getBytes(StandardCharsets.UTF_8));
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (digest[i] & 0xFFL);
        }
        // value 可能为负，取无符号模
        return (int) Math.floorMod(value, BUCKET_COUNT);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
