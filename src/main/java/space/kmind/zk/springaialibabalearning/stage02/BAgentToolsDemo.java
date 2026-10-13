package space.kmind.zk.springaialibabalearning.stage02;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * 3.3 Agent + Tools：天气 + 订单，metadata 传 userId，带调用次数上限。
 * 3.4 结构化：ChatClient.entity，不要挂在带 Tools 的 ReactAgent 上。
 */
@Tag(name = "Stage02-3.3Agent + Tools", description = "Agent + Tools")
@RestController
public class BAgentToolsDemo {

    public static final String SYSTEM_PROMPT = """
            你是电商助手。只处理天气与订单。
            问天气必须调用 get_weather；问订单必须调用 get_orders。
            禁止编造气温与订单。
            工具返回 error=true 或 empty=true 时，如实向用户说明，不要编造数据，也不要重复调用同一个工具超过一次。
            工具返回 timeout=true 时，告知用户"查询超时，请稍后重试"。
            无关问题礼貌拒绝。
            """;

    private static final String STRUCT_PROMPT = """
            把助手回复整理为 AgentReply 字段。
            weather / ordersJson 只能来自原文中的事实，禁止编造。
            没有对应信息时填空字符串；需要人工时 needHuman=true。
            status 填 success 或 error；有错误时填写 errorMessage。
            """;

    private final ReactAgent agent;
    private final ChatClient chatClient;

    public BAgentToolsDemo(ChatModel chatModel) throws Exception {
        this.chatClient = ChatClient.builder(chatModel).build();

        ToolCallback weather = FunctionToolCallback
                .builder("get_weather", new WeatherTool())
                .description("根据城市名查询当前天气。用户问气温、带伞时使用。")
                .inputType(WeatherRequest.class)
                .build();

        ToolCallback orders = FunctionToolCallback
                .builder("get_orders", new OrderTool())
                .description("查询当前登录用户的订单列表；不要让用户传 userId。")
                .inputType(OrderRequest.class)
                .build();

        ModelCallLimitHook limitHook = ModelCallLimitHook.builder()
                .runLimit(5) // 单次 agent 运行内，模型最多调用 5 次
                //达到上限后框架采取的行为。常见的选项包括：
                //ERROR	直接抛出异常，中断执行流，由调用方捕获处理
                //END	不抛异常，而是以当前已有的消息状态优雅结束，返回一个“已达调用上限”的提示
                //ERROR 适合开发调试阶段，能让你立刻发现“Agent 没有在合理步数内收敛”的问题。生产环境如果不想让异常冒泡到前端，可以改用 END，让 Agent 输出一段友好的提示。
                .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
                .build();

        // 注意：这里不要 .outputType(AgentReply.class)
        this.agent = ReactAgent.builder()
                .name("commerce_agent")
                .model(chatModel)
                .systemPrompt(SYSTEM_PROMPT)
                .tools(weather, orders)
                .hooks(limitHook)
                .saver(new MemorySaver())
                .build();
    }

    @Operation(summary = "天气/订单 Agent（自然语言）")
    @GetMapping("/api/s33/chat")
    public String chat(
            @RequestParam(defaultValue = "成都天气怎么样？我有待发货订单吗？") String q,
            @RequestParam(defaultValue = "t-commerce-1") String threadId,
            @RequestParam(defaultValue = "10086") String userId) throws Exception {

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("user_id", userId)
                .build();
        AssistantMessage msg = agent.call(q, config);
        return msg.getText();
    }

    @Operation(summary = "天气/订单 Agent（结构化 POJO）")
    @GetMapping("/api/s34/reply")
    public AgentReply reply(
            @RequestParam(defaultValue = "成都天气怎么样？我有待发货订单吗？") String q,
            @RequestParam(defaultValue = "t-commerce-1") String threadId,
            @RequestParam(defaultValue = "10086") String userId) throws Exception {

        // 第一步：ReAct + Tools，拿自然语言结论
        String plain = chat(q, threadId, userId);

        // 第二步：ChatClient 结构化（与SpringAi底座篇 .entity(Class) 一致）
        return chatClient.prompt()
                .system(STRUCT_PROMPT)
                .user(plain)
                .call()
                .entity(AgentReply.class);
    }


    /**
     * 天气工具输入
     */
    public static class WeatherRequest {
        @ToolParam(description = "城市中文名，如成都、上海")
        private String city;

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }

    /**
     * 订单工具输入
     */
    public static class OrderRequest {
        @ToolParam(description = "可选过滤，如 pending", required = false)
        private String query;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }
    }

    // ============================================================
    // 工具返回的统一协议 + 超时控制
    // ============================================================

    static String okJson(String payload) {
        return payload;
    }

    static String errorJson(String code, String message) {
        return "{\"error\":true,\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
    }

    static String emptyJson(String message) {
        return "{\"error\":false,\"empty\":true,\"message\":\"" + message + "\"}";
    }

    static String timeoutJson(String message) {
        return "{\"error\":true,\"timeout\":true,\"message\":\"" + message + "\"}";
    }

    static <T> T callWithTimeout(Supplier<T> task, long timeoutMs) throws Exception {
        try {
            return CompletableFuture.supplyAsync(task)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ============================================================
    // 工具实现
    // ============================================================

    static class WeatherTool implements BiFunction<WeatherRequest, ToolContext, String> {
        @Override
        public String apply(WeatherRequest req, ToolContext toolContext) {
            try {
                String city = req == null ? null : req.getCity();
                if (city == null || city.isBlank()) {
                    return errorJson("INVALID_ARGUMENT", "未提供城市名");
                }

                String result = callWithTimeout(() -> queryWeather(city.trim()), 2000);
                if (result == null || result.isBlank()) {
                    return emptyJson("未查询到该城市的天气数据");
                }
                return okJson(result);

            } catch (TimeoutException e) {
                return timeoutJson("天气服务响应超时");
            } catch (Exception e) {
                return errorJson("SERVICE_UNAVAILABLE", "天气服务暂时不可用，请稍后重试");
            }
        }

        private String queryWeather(String city) {
            return switch (city) {
                case "成都" -> "成都：晴，25℃，东北风2级";
                case "上海" -> "上海：多云，22℃";
                default -> city + "：晴间多云，20℃（示例）";
            };
        }
    }

    static class OrderTool implements BiFunction<OrderRequest, ToolContext, String> {
        @Override
        public String apply(OrderRequest req, ToolContext toolContext) {
            try {
                String userId = resolveUserId(toolContext);
                String query = req == null ? null : req.getQuery();

                String payload = callWithTimeout(() -> queryOrders(userId, query), 2000);
                if (payload == null || "[]".equals(payload)) {
                    return emptyJson("查询成功，但当前没有订单记录");
                }
                return okJson(payload);

            } catch (TimeoutException e) {
                return timeoutJson("订单服务响应超时");
            } catch (Exception e) {
                return errorJson("ORDER_SERVICE_ERROR", "订单查询失败，请稍后重试");
            }
        }

        private String resolveUserId(ToolContext ctx) {
            if (ctx == null || ctx.getContext() == null) {
                return "anonymous";
            }
            Object raw = ctx.getContext().get("user_id");
            return raw != null ? String.valueOf(raw) : "anonymous";
        }

        private String queryOrders(String userId, String query) {
            if ("10086".equals(userId)) {
                return "[{\"orderId\":\"O20260409\",\"status\":\"pending_shipment\",\"amount\":199.0}]";
            }
            return "[]";
        }
    }

    /** 3.4 结构化输出 POJO：字段保持简单 */
    public static class AgentReply {
        private String summary;
        private String weather;
        private String ordersJson;
        private boolean needHuman;
        private String status;
        private String errorMessage;

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public String getWeather() { return weather; }
        public void setWeather(String weather) { this.weather = weather; }
        public String getOrdersJson() { return ordersJson; }
        public void setOrdersJson(String ordersJson) { this.ordersJson = ordersJson; }
        public boolean isNeedHuman() { return needHuman; }
        public void setNeedHuman(boolean needHuman) { this.needHuman = needHuman; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    }
}