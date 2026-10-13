package space.kmind.zk.springaialibabalearning.stage02;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * @author zk
 * @date 2026/10/10 16:30
 */
@Tag(name = "Stage02-2.1 Agent 基础", description = "ReactAgent 主线")
@RestController
public class AAgentBasicsDemo {

    private final ReactAgent agent;

    static class WeatherRequest {
        @ToolParam(description = "城市中文名，如：成都、上海")
        private String city;
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
    }

    public AAgentBasicsDemo(ChatModel chatModel) throws Exception {
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
                .name("weather_agent")
                .model(chatModel)
//                .systemPrompt("你是天气助手。查天气必须调用 get_weather，禁止编造气温。")
                .systemPrompt("你是天气助手。查天气必须调用 get_weather，禁止编造气温。"
                        + "当用户提到自己所在的城市时，查询对应天气后，要记住这个地点。"
                        + "后续用户问“我所在地/我这里/本地”的天气时，默认使用之前记住的城市。")
//                .instruction("用户所在地：{city}。用户问题：{input}") // 关键：显式引用 city
                .tools(weatherTool)
                .saver(new MemorySaver())
                .build();
    }

    @Operation(summary = "call：只要最终助手消息")
    @GetMapping("/api/s31/call")
    public String call(
            @Parameter(description = "用户问题")
            @RequestParam(defaultValue = "成都今天天气怎么样？") String q) throws Exception {
        AssistantMessage msg = agent.call(q);
        return msg.getText();
    }

    @Operation(summary = "invoke：看完整 OverAllState（含 messages）")
    @GetMapping("/api/s31/invoke")
    public String invoke(
            @RequestParam(defaultValue = "上海要不要带伞？") String q) throws Exception {
        Optional<OverAllState> opt = agent.invoke(q);
        if (opt.isEmpty()) {
            return "empty state";
        }
        OverAllState state = opt.get();
        List<Message> messages = state.value("messages", new ArrayList<>());
        // 学习阶段直接 toString；生产请结构化打印 role/content/toolCalls
        return "messageCount=" + messages.size() + "\n" + messages;
    }

    @Operation(summary = "同一 threadId 多轮续聊")
    @GetMapping("/api/s31/thread")
    public String thread(
            @RequestParam(defaultValue = "我在成都") String q,
            @RequestParam(defaultValue = "t-001") String threadId) throws Exception {
        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .build();
        return agent.call(q, config).getText();
    }

//    @Operation(summary = "同一 threadId 多轮续聊")
//    @GetMapping("/api/s31/thread")
//    public String thread() throws Exception {
//        RunnableConfig config = RunnableConfig.builder()
//                .threadId("t-001")
//                .build();
//        // 第一轮：传入用户所在地
//        Map<String, Object> inputs = new HashMap<>();
//        inputs.put("input", "我所在地天气");
//        inputs.put("city", "成都");
//        return agent.call(inputs, config).getText();
//    }

}