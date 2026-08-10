package com.llmwiki.background;

/**
 * 表示外部模型服务因账户额度或时间窗口限制拒绝请求。
 * 与密钥解密异常分开，前端和后台记录可以给出可执行的额度恢复提示。
 */
public class ModelQuotaExceededException extends IllegalStateException {
    /** 创建模型额度异常。 */
    public ModelQuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
