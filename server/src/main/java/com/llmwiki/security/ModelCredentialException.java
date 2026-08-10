package com.llmwiki.security;

/**
 * 表示模型密钥缺失、格式损坏或无法用当前主密钥解密。
 * 独立异常类型让后台调度器可以自动暂停不可恢复的定时任务，而不会误伤普通网络故障。
 */
public class ModelCredentialException extends IllegalStateException {
    /**
     * 创建不含底层密码学细节的模型凭据异常。
     *
     * @param message 可安全写入后台运行记录的错误说明
     */
    public ModelCredentialException(String message) {
        super(message);
    }

    /**
     * 创建带内部原因的模型凭据异常，原因只进入服务端错误日志。
     *
     * @param message 可安全展示的错误说明
     * @param cause 底层解密异常
     */
    public ModelCredentialException(String message, Throwable cause) {
        super(message, cause);
    }
}
