package com.phoenix.mcp.diagnostic.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenix.mcp.diagnostic.config.DiagnosticProperties;
import com.phoenix.mcp.diagnostic.service.DiagnosticService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用实际 HTTP、Redis PING 和受限文件读取生成证据；目标异常也是有效采集结果。
 */
@Service
public class DiagnosticServiceImpl implements DiagnosticService {

    private static final int MAX_HTTP_BYTES = 8192;
    private static final int MAX_LOG_BYTES = 64 * 1024;
    private static final Pattern SAFE_LOG = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T[0-9:.+Z-]{12,23} (?:INFO|WARN) +"
                    + "event=PRODUCT_QUERY_(?:OK application=phoenix-demo dependency=redis productId=1"
                    + "|FAILED application=phoenix-demo dependency=redis code=REDIS_UNAVAILABLE productId=1)$");

    private final DiagnosticProperties properties;
    private final ObjectMapper json;

    /**
     * 注入可信目标配置和 JSON 解析器。
     */
    public DiagnosticServiceImpl(DiagnosticProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    /**
     * 分别检查进程存活、应用健康与实际业务，避免把存活等同于可用。
     */
    @Override
    public Map<String, Object> inspectApplication(String targetId) {
        requireTarget(targetId);
        var probes = List.of(probe("liveness", "/api/demo/ping"), probe("health", "/actuator/health"),
                probe("product", "/api/demo/products/1"));
        String status = probes.stream().allMatch(item -> "UP".equals(item.get("status"))) ? "UP"
                : "UP".equals(probes.getFirst().get("status")) ? "DEGRADED" : "DOWN";
        var result = evidence("application", status);
        result.put("probes", probes);
        return result;
    }

    /**
     * 只发送固定 RESP PING，不接受命令或凭据；当前限定本地无密码演示 Redis。
     */
    @Override
    public Map<String, Object> inspectRedis(String targetId) {
        requireTarget(targetId);
        long started = System.nanoTime();
        String code;
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.redisHost(), properties.redisPort()), timeoutMillis());
            socket.setSoTimeout(timeoutMillis());
            socket.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = readRedisLine(socket);
            code = "+PONG\r\n".equals(response) ? "PONG"
                    : response.startsWith("-NOAUTH ") ? "AUTH_REQUIRED" : "UNEXPECTED_RESPONSE";
        } catch (SocketTimeoutException exception) {
            code = "TIMEOUT";
        } catch (ConnectException exception) {
            code = "CONNECTION_REFUSED";
        } catch (IOException exception) {
            code = "CONNECTION_FAILED";
        }
        var result = evidence("redis", "PONG".equals(code) ? "UP" : "DOWN");
        result.put("code", code);
        result.put("durationMs", elapsed(started));
        return result;
    }

    /**
     * 最多读取文件末尾 64 KiB，只返回固定事件，拒绝符号链接并排除陌生日志内容。
     */
    @Override
    public Map<String, Object> readRecentLogs(String targetId, int lines) {
        requireTarget(targetId);
        if (lines < 1 || lines > 80) {
            throw new IllegalArgumentException("日志条数须在 1 到 80 之间");
        }
        var file = properties.logPath();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return logFailure("MISSING", "LOG_NOT_FOUND");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return logFailure("UNAVAILABLE", "LOG_NOT_REGULAR_FILE");
        }
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            long start = Math.max(0, size - MAX_LOG_BYTES);
            channel.position(start);
            var buffer = ByteBuffer.allocate((int) (size - start));
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) {
                    break;
                }
            }
            buffer.flip();
            String text = StandardCharsets.UTF_8.decode(buffer).toString();
            if (start > 0) {
                int boundary = text.indexOf('\n');
                text = boundary < 0 ? "" : text.substring(boundary + 1);
            }
            var accepted = text.lines().filter(line -> SAFE_LOG.matcher(line).matches()).toList();
            var selected = new ArrayList<>(accepted.subList(Math.max(0, accepted.size() - lines), accepted.size()));
            boolean truncated = start > 0 || accepted.size() > lines;
            while (String.join("\n", selected).length() > 6000) {
                selected.removeFirst();
                truncated = true;
            }
            var result = evidence("logs", "AVAILABLE");
            result.put("lines", selected);
            result.put("returnedLines", selected.size());
            result.put("truncated", truncated);
            result.put("scope", "仅包含商品查询诊断事件；历史错误不等于当前故障");
            return result;
        } catch (IOException | SecurityException exception) {
            return logFailure("UNAVAILABLE", "LOG_READ_FAILED");
        }
    }

    /**
     * 检查固定路径，禁用重定向、代理和缓存，只输出经过验证的状态代码。
     */
    private Map<String, Object> probe(String name, String path) {
        long started = System.nanoTime();
        HttpURLConnection connection = null;
        int statusCode = 0;
        String code;
        boolean up = false;
        try {
            connection = (HttpURLConnection) properties.applicationBaseUrl().resolve(path).toURL()
                    .openConnection(java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(timeoutMillis());
            connection.setReadTimeout(timeoutMillis());
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            statusCode = connection.getResponseCode();
            if (statusCode >= 300 && statusCode < 400) {
                code = "REDIRECT_BLOCKED";
            } else {
                var stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) {
                    throw new IOException("响应体缺失");
                }
                try (stream; var body = new ByteArrayOutputStream()) {
                    long deadline = System.nanoTime() + properties.probeTimeout().toNanos();
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (System.nanoTime() >= deadline) {
                            throw new SocketTimeoutException();
                        }
                        if (body.size() + count > MAX_HTTP_BYTES) {
                            throw new IOException("响应过大");
                        }
                        body.write(buffer, 0, count);
                    }
                    var node = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(body.toByteArray());
                    up = statusCode == 200 && node != null && node.isObject() && switch (name) {
                        case "liveness" -> "phoenix-demo".equals(node.path("application").asText());
                        case "health" -> "UP".equals(node.path("status").asText());
                        case "product" -> node.path("id").asLong() == 1 && node.path("name").isTextual();
                        default -> false;
                    };
                    code = up ? "OK" : statusCode == 503 && node != null
                            && "REDIS_UNAVAILABLE".equals(node.path("code").asText()) ? "REDIS_UNAVAILABLE"
                            : "UNEXPECTED_RESPONSE";
                }
            }
        } catch (SocketTimeoutException exception) {
            code = "TIMEOUT";
        } catch (ConnectException exception) {
            code = "CONNECTION_REFUSED";
        } catch (IOException exception) {
            code = "REQUEST_FAILED";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return Map.of("name", name, "status", up ? "UP" : "DOWN", "code", code,
                "httpStatus", statusCode, "durationMs", elapsed(started));
    }

    /**
     * 限制 Redis 响应行长和总读取时间，绝不把服务端任意文本回传给模型。
     */
    private String readRedisLine(Socket socket) throws IOException {
        long deadline = System.nanoTime() + properties.probeTimeout().toNanos();
        var bytes = new ByteArrayOutputStream();
        while (bytes.size() < 256) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new SocketTimeoutException();
            }
            socket.setSoTimeout((int) Math.max(1, remaining / 1_000_000));
            int next = socket.getInputStream().read();
            if (next == -1) {
                break;
            }
            bytes.write(next);
            if (next == '\n') {
                break;
            }
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    /**
     * 在任何网络或文件操作前校验固定目标编号。
     */
    private void requireTarget(String targetId) {
        if (!DiagnosticProperties.TARGET_ID.equals(targetId)) {
            throw new IllegalArgumentException("目标未登记或不允许访问");
        }
    }

    /**
     * 统一标注证据来源和采集时刻，不表示原子化快照或诊断结论。
     */
    private Map<String, Object> evidence(String kind, String status) {
        var result = new LinkedHashMap<String, Object>();
        result.put("targetId", DiagnosticProperties.TARGET_ID);
        result.put("kind", kind);
        result.put("status", status);
        result.put("observedAt", Instant.now().toString());
        return result;
    }

    /**
     * 日志不可读取仍返回明确证据，不能被解释为没有错误。
     */
    private Map<String, Object> logFailure(String status, String code) {
        var result = evidence("logs", status);
        result.put("code", code);
        result.put("lines", List.of());
        return result;
    }

    /**
     * 将配置转换为 Socket 和 HTTP 使用的毫秒超时。
     */
    private int timeoutMillis() {
        return (int) properties.probeTimeout().toMillis();
    }

    /**
     * 使用单调时钟记录采集耗时。
     */
    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
