package com.llmwiki.security;

import com.llmwiki.config.LlmWikiProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 使用 AES-GCM 加密需要持久化的模型 API Key，数据库只保存带随机 IV 的认证密文。
 */
@Component
public class SecretCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** 为每次加密生成不可预测 IV，确保相同 API Key 不会产生相同密文。 */
    private final SecureRandom secureRandom = new SecureRandom();
    /** 从外部秘密派生的进程内 AES 密钥；从不写入数据库或日志。 */
    private final SecretKeySpec key;

    /**
     * 从独立模型加密密钥构造 AES 密钥；开发环境未单独配置时可回退到 JWT 外部密钥。
     *
     * @param properties 应用外部配置
     */
    public SecretCipher(LlmWikiProperties properties) {
        String source = properties.llm().encryptionKey();
        this.key = source == null || source.isBlank() ? null : new SecretKeySpec(sha256(source), "AES");
    }

    /**
     * 加密模型秘密。
     *
     * @param plaintext 原始 API Key
     * @return Base64 编码的 IV 与认证密文
     */
    public String encrypt(String plaintext) {
        if (key == null) {
            throw new ModelCredentialException("模型加密主密钥未配置，暂时不能保存接口密钥");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (Exception exception) {
            throw new ModelCredentialException("模型接口密钥加密失败", exception);
        }
    }

    /**
     * 解密模型秘密并校验 GCM 认证标签。
     *
     * @param encoded 数据库存储值
     * @return 原始 API Key
     */
    public String decrypt(String encoded) {
        if (key == null) {
            throw new ModelCredentialException("模型加密主密钥不可用，请检查服务端配置");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new ModelCredentialException("模型接口密钥已失效，请重新输入密钥并测试保存", exception);
        }
    }

    /** 对任意长度外部密钥做固定长度 SHA-256 派生。 */
    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
