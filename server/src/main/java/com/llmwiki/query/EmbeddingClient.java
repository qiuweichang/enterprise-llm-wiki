package com.llmwiki.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.config.LlmWikiProperties;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 调用独立 Python CPU 模型；严格核对模型标识、维度及单位范数，防止异构向量混用。 */
@Component
public class EmbeddingClient {
    /** 与 Python 模型及分块规则一同版本化的索引协议。 */
    public static final String MODEL_ID = "bge-small-zh-v1.5-fastembed074-chunk320-v1";
    public static final int DIMENSIONS = 512;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();

    /** 复用已配置的 Python 地址，避免另设一组服务连接参数。 */
    public EmbeddingClient(LlmWikiProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.endpoint = URI.create(properties.python().baseUrl()).resolve("/embeddings");
    }

    /** 在数据库事务外向量化有界文本批次；查询用短超时，后台加载模型允许较长等待。 */
    public List<List<Double>> embed(List<String> texts, boolean query) {
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(query ? 10 : 90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(
                            Map.of("texts", texts, "kind", query ? "query" : "passage")))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IllegalStateException("语义服务不可用（HTTP " + response.statusCode() + "），请检查 Python 模型服务");
            var data = mapper.readValue(response.body(), Embeddings.class);
            if (!MODEL_ID.equals(data.modelId()) || data.dimensions() != DIMENSIONS || data.vectors().size() != texts.size())
                throw new IllegalStateException("语义模型标识或维度不一致，请部署匹配的 Python 服务");
            for (var vector : data.vectors()) {
                double norm = vector.stream().mapToDouble(v -> v * v).sum();
                if (vector.size() != DIMENSIONS || !Double.isFinite(norm) || Math.abs(norm - 1) > 0.01)
                    throw new IllegalStateException("语义服务返回了无效向量");
            }
            return data.vectors();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("语义请求已中断", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("语义服务连接失败或超时，请检查 Python 服务（8101）", exception);
        }
    }

    /** 将校验后的向量转为 PostgreSQL 数组字面量，由 JDBC 参数绑定而非 SQL 拼接。 */
    public static String array(List<Double> vector) {
        return "{" + String.join(",", vector.stream().map(String::valueOf).toList()) + "}";
    }

    /** 把全文切成 320 Unicode 码点、重叠 48 码点的块；保留尾部，不按整个文档截断。 */
    public static List<String> chunks(String title, String markdown) {
        String prefix = title.codePoints().limit(64).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        List<String> result = new ArrayList<>();
        // 段落优先：不能把尾部短而重要的新主题淹没在上一段长正文中。
        String content=markdown == null || markdown.isBlank() ? title : markdown;
        for (String paragraph:content.split("\\R+")) {
            if(paragraph.isBlank()) continue;
            int[] points=paragraph.codePoints().toArray();
            for (int start = 0; start < points.length; start += 272) {
                int length = Math.min(320, points.length - start);
                result.add(prefix + "\n" + new String(points, start, length));
                if (start + length == points.length) break;
            }
        }
        return result;
    }

    /** Python 返回的固定协议载荷。 */
    public record Embeddings(String modelId, int dimensions, List<List<Double>> vectors) { }
}
