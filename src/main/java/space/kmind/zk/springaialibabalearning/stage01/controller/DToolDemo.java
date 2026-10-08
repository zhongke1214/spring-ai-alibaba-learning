package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
/**
 * @author zk
 * @date 2026/9/30 11:16
 */
@Component
class WeatherTools {

    @Tool(description = "查询城市当前天气。用户问气温、是否下雨、是否带伞时使用。")
    public String getWeather(
            @ToolParam(description = "城市中文名，如杭州、上海") String city,
            ToolContext toolContext) {
        // ToolContext 来自调用方传入的上下文，不是模型编的
        String userId = String.valueOf(toolContext.getContext().getOrDefault("userId", "anonymous"));
        // 学习阶段写死返回；真实项目这里调 HTTP
        String weather = switch (city == null ? "" : city.trim()) {
            case "杭州" -> "晴，24°C，东北风2级";
            case "上海" -> "多云，22°C，东风3级";
            default -> city + "：晴间多云，20°C（示例数据）";
        };
        return "user=" + userId + ", " + weather;
    }
}

@Tag(name = "Stage01-1.4 Tool Calling", description = "工具调用")
@RestController
public class DToolDemo {

    private final ChatClient chatClient;
    private final WeatherTools weatherTools;

    public DToolDemo(ChatClient.Builder builder, WeatherTools weatherTools) {
        this.weatherTools = weatherTools;
        this.chatClient = builder
                .defaultSystem("你是助手。问天气必须调用 getWeather，禁止编造气温。")
                .defaultTools(weatherTools) // 注册工具；ChatClient 会跑工具循环
                .build();
    }

    @GetMapping("/api/s14/weather")
    public String weather(@RequestParam(defaultValue = "杭州今天要不要带伞？") String q,
                          @RequestParam(defaultValue = "u1001") String userId) {
        return chatClient.prompt()
                .user(q)
                // 把业务身份塞进 ToolContext，工具方法里能读到
                .toolContext(Map.of("userId", userId))
                .call()
                .content();
    }
}