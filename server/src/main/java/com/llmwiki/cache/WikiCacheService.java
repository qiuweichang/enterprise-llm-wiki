package com.llmwiki.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 封装 Redis 读写与工作空间版本戳失效策略。
 * Redis 不可用时只影响命中率，不影响数据库作为事实来源的正确性。
 */
@Service
public class WikiCacheService {
    private static final Logger log = LoggerFactory.getLogger(WikiCacheService.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 创建缓存服务。
     *
     * @param redis Redis 字符串客户端
     * @param objectMapper JSON 序列化器
     */
    public WikiCacheService(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取 JSON 缓存。
     *
     * @param key 缓存键
     * @param type 目标 Java 类型
     * @return 命中且反序列化成功时返回值
     */
    public <T> Optional<T> get(String key, JavaType type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, type));
        } catch (Exception exception) {
            log.error("Redis cache read failed key={}", key, exception);
            return Optional.empty();
        }
    }

    /**
     * 写入有界生命周期的 JSON 缓存。
     *
     * @param key 缓存键
     * @param value 值
     * @param ttl 生命周期
     */
    public void put(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize cache value", exception);
        } catch (Exception exception) {
            log.error("Redis cache write failed key={}", key, exception);
        }
    }

    /**
     * 返回工作空间知识版本戳，用于让所有查询缓存随一次发布统一失效。
     */
    public long workspaceEpoch(UUID organizationId, UUID workspaceId) {
        String key = epochKey(organizationId, workspaceId);
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception exception) {
            log.error("Redis workspace epoch read failed organizationId={} workspaceId={}", organizationId, workspaceId, exception);
            return 0L;
        }
    }

    /**
     * 发布知识变更后递增版本戳；旧缓存自然过期，无需使用代价高昂的 KEYS 扫描。
     */
    public void advanceWorkspaceEpoch(UUID organizationId, UUID workspaceId) {
        try {
            redis.opsForValue().increment(epochKey(organizationId, workspaceId));
        } catch (Exception exception) {
            log.error("Redis workspace epoch update failed organizationId={} workspaceId={}", organizationId, workspaceId, exception);
        }
    }

    /** 组装租户级版本戳键。 */
    private String epochKey(UUID organizationId, UUID workspaceId) {
        return "llm-wiki:epoch:" + organizationId + ":" + workspaceId;
    }
}

