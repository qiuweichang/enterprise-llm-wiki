package com.llmwiki.security;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

/**
 * 使用 BCrypt 完成密码单向哈希与常量时间校验，认证流程不依赖 Spring Security。
 */
@Service
public class PasswordService {

    /**
     * 生成密码哈希。
     *
     * @param password 明文密码，仅在调用栈中短暂存在
     * @return 可持久化的 BCrypt 哈希
     */
    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray());
    }

    /**
     * 校验明文密码是否与哈希匹配。
     *
     * @param password 待校验明文
     * @param hash 数据库中的 BCrypt 哈希
     * @return 匹配时为 true
     */
    public boolean verify(String password, String hash) {
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
    }
}

