package com.phoenix.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenix.server.chat.service.impl.ReadOnlyToolPolicy;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证控制端独立于模型和工具描述的只读授权与证据校验。
 */
class ReadOnlyToolPolicyTests {

    private final ReadOnlyToolPolicy policy = new ReadOnlyToolPolicy(new ObjectMapper());

    /**
     * 三个诊断工具都只接受固定目标编号。
     */
    @ParameterizedTest
    @ValueSource(strings = {"inspect_application", "inspect_redis", "read_recent_logs"})
    void shouldAcceptRegisteredTarget(String name) {
        assertThatCode(() -> policy.validateArguments(request(name, "{\"targetId\":\"demo-local\"}"))).doesNotThrowAnyException();
    }

    /**
     * 日志条数支持限定范围的 JSON 整数。
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 40, 80})
    void shouldAcceptBoundedLogLines(int lines) {
        assertThatCode(() -> policy.validateArguments(request("read_recent_logs",
                "{\"targetId\":\"demo-local\",\"lines\":" + lines + "}"))).doesNotThrowAnyException();
    }

    /**
     * 未登记目标、额外字段和不完整参数都不能到达工具服务。
     */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"targetId\":null}", "{\"targetId\":\"unknown\"}",
            "{\"targetId\":\"demo-local\",\"url\":\"http://localhost\"}", "{\"targetId\":\"demo-local\",\"path\":\"../secrets\"}",
            "{\"targetId\":\"demo-local\",\"command\":\"stop\"}", "{\"targetId\":\"demo-local\"} {}",
            "{\"targetId\":\"unknown\",\"targetId\":\"demo-local\"}", "{\"targetId\":\"demo-local\",\"lines\":1}"})
    void shouldRejectUnsafeTargetArguments(String arguments) {
        assertThatThrownBy(() -> policy.validateArguments(request("inspect_application", arguments))).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 小数、字符串、空值和超范围条数不会隐式转换为整数。
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "81", "-1", "1.0", "1.5", "\"40\"", "null", "true", "4294967297"})
    void shouldRejectInvalidLogLines(String lines) {
        assertThatThrownBy(() -> policy.validateArguments(request("read_recent_logs",
                "{\"targetId\":\"demo-local\",\"lines\":" + lines + "}"))).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 故障证据也可成功返回模型，UP 不得搭配连接失败。
     */
    @Test
    void shouldAcceptDownEvidenceButRejectContradictoryRedisState() {
        var request = request("inspect_redis", "{\"targetId\":\"demo-local\"}");
        String down = "{\"targetId\":\"demo-local\",\"kind\":\"redis\",\"observedAt\":\"2026-10-17T00:00:00Z\",\"status\":\"DOWN\",\"code\":\"CONNECTION_REFUSED\"}";
        assertThatCode(() -> policy.validateResult(request, down)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateResult(request, down.replace("DOWN", "UP"))).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 目标、来源或采集时刻缺失时不能当作有效证据。
     */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"targetId\":\"other\",\"kind\":\"redis\"}",
            "{\"targetId\":\"demo-local\",\"kind\":\"logs\"}", "{\"targetId\":\"demo-local\",\"kind\":\"redis\",\"observedAt\":\"invalid\"}"})
    void shouldRejectInvalidEvidence(String text) {
        assertThatThrownBy(() -> policy.validateResult(request("inspect_redis", "{}"), text)).isInstanceOf(RuntimeException.class);
    }

    /**
     * 三个空探测或重复探测不能拼成看似正常的应用检查结果。
     */
    @Test
    void shouldRejectIncompleteApplicationProbes() {
        String text = "{\"targetId\":\"demo-local\",\"kind\":\"application\",\"observedAt\":\"2026-10-17T00:00:00Z\",\"status\":\"UP\",\"probes\":[{},{},{}]}";
        assertThatThrownBy(() -> policy.validateResult(request("inspect_application", "{}"), text)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 日志不能包含非文本对象，缺失状态也不能缺少原因。
     */
    @ParameterizedTest
    @ValueSource(strings = {"\"status\":\"AVAILABLE\",\"lines\":[{}]", "\"status\":\"MISSING\",\"lines\":[]"})
    void shouldRejectInvalidLogEvidence(String fields) {
        String text = "{\"targetId\":\"demo-local\",\"kind\":\"logs\",\"observedAt\":\"2026-10-17T00:00:00Z\"," + fields + "}";
        assertThatThrownBy(() -> policy.validateResult(request("read_recent_logs", "{}"), text)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 构造没有外部副作用的模型工具请求。
     */
    private ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder().id("test").name(name).arguments(arguments).build();
    }
}
