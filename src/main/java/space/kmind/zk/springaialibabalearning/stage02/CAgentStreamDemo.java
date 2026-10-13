package space.kmind.zk.springaialibabalearning.stage02;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * @author zk
 * @date 2026/10/10 16:30
 */
@Tag(name = "Stage02-3.6 流式 Agent", description = "stream + SSE")
@RestController
public class CAgentStreamDemo {

    private final ReactAgent agent;

    static class WeatherRequest {
        @ToolParam(description = "城市中文名，如：成都、上海")
        private String city;
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
    }

    public CAgentStreamDemo(ChatModel chatModel) throws Exception {
        //这里模拟天气tools
        ToolCallback weatherTool = FunctionToolCallback
                .builder("get_weather", (WeatherRequest city, ToolContext ctx) ->
                        switch (city == null ? "" : city.getCity().trim()) {
                            case "成都" -> "成都：晴，25℃，东北风 2 级";
                            case "上海" -> "上海：多云，22℃";
                            default -> city + "：晴间多云，20℃（示例数据）";
                        })
                .description("查询指定城市天气，city 为城市中文名")
                .inputType(WeatherRequest.class)
                .build();

        this.agent = ReactAgent.builder()
                .name("stream_weather_agent")
                .model(chatModel)
                .systemPrompt("你是天气助手。查天气必须调用工具。回答简洁。")
                .tools(weatherTool)
                .saver(new MemorySaver())
                .build();
    }

    @Operation(summary = "Agent SSE：只推模型增量")
    @GetMapping(value = "/api/s36/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sse(
            @RequestParam(defaultValue = "成都天气怎么样，用两句话回答") String q,
            @RequestParam(defaultValue = "t-stream-1") String threadId) {

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .build();

        Flux<NodeOutput> stream;
        try {
            stream = agent.stream(q, config);
        } catch (Exception e) {
            return Flux.error(e);
        }

        return stream
                .filter(o -> o instanceof StreamingOutput<?> so
                        && so.getOutputType() == OutputType.AGENT_MODEL_STREAMING)
                .map(o -> {
                    String delta = ((StreamingOutput<?>) o).message().getText();
                    return ServerSentEvent.builder(delta == null ? "" : delta)
                            .event("delta")
                            .build();
                })
                .concatWithValues(ServerSentEvent.builder("[DONE]").event("done").build());
    }
}