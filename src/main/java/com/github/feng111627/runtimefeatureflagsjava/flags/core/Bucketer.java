package com.github.feng111627.runtimefeatureflagsjava.flags.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 稳定分桶器：对 (开关名, 用户标识) 做 SHA-256 哈希，映射到 0-99 的桶。
 * 同一输入在任何进程、任何时刻得到同一个桶，保证判定结果一致。
 */
public final class Bucketer {

    private static final int BUCKET_COUNT = 100;

    private Bucketer() {
    }

    public static int bucketOf(String flagName, String userId) {
        byte[] digest = sha256(flagName + ":" + userId);
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (digest[i] & 0xFFL);
        }
        return (int) Math.floorMod(value, BUCKET_COUNT);
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
