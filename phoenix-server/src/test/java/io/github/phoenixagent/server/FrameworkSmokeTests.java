package io.github.phoenixagent.server;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 使用本地构建与确定性节点验证基础框架，不调用真实模型。
 */
class FrameworkSmokeTests {

    /**
     * 验证 OpenAI 兼容客户端可以构建，但不发送请求。
     */
    @Test
    void shouldBuildModelClientWithoutSendingRequests() {
        var model = OpenAiChatModel.builder()
                .baseUrl("http://127.0.0.1:1/v1")
                .apiKey("test-placeholder-not-a-secret")
                .modelName("test-model")
                .build();

        assertThat(model).isNotNull();
    }

    /**
     * 验证图能执行节点并把状态传递到结束位置。
     */
    @Test
    void shouldExecuteMinimalGraph() throws Exception {
        var workflow = new StateGraph<>(AgentState::new)
                .addNode("initialize", node_async(state -> Map.of("stage", "READY")))
                .addEdge(START, "initialize")
                .addEdge("initialize", END)
                .compile();

        var result = workflow.invoke(Map.of("stage", "CREATED")).orElseThrow();

        assertThat(result.<String>value("stage")).contains("READY");
    }
}
