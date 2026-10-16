package space.kmind.zk.springaialibabalearning.stage04;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

@Tag(name = "Stage04-Hooks/HITL/Context", description = "客服：退款审批 + 压缩 + 重试 + Skills")
@RestController
public class ACustomerServiceHookDemo {
    final AtomicInteger queryFailBudget = new AtomicInteger(1);

    public static final String SYSTEM_PROMPT = """
            你是电商客服 Agent。
            查订单必须调用 query_order；退款必须调用 refund_order，禁止口头承诺已退款。
            用户询问退款规则、可否退款、几日内可退时：先调用 read_skill 读取 refund-policy，再基于技能内容回答。
            工具返回 error 时如实说明；无关问题礼貌拒绝。
            """;

    private final ReactAgent agent;
    private final BaseCheckpointSaver saver;

    /**
     * 演示用：模拟工具前两次失败
     */

    public ACustomerServiceHookDemo(ChatModel chatModel, RedisSaver redisSaver) throws Exception {
        this.saver = redisSaver;
        ToolCallback queryOrder = FunctionToolCallback
                .builder("query_order", new QueryOrder())
                .description("根据订单号查询订单状态与金额。用户问订单、物流、能否退款时先查。")
                .inputType(OrderQuery.class)
                .build();

        ToolCallback refundOrder = FunctionToolCallback
                .builder("refund_order", this::refundOrder)
                .description("对指定订单发起退款。高风险写操作，仅在用户明确要求退款时调用。")
                .inputType(RefundRequest.class)
                .build();

        // Skills：元数据进提示词；需要细则时模型调 read_skill（由 SkillsAgentHook 自动注册）
        // 开发期用 Classpath；若打包成 jar 后加载失败，改用 FileSystemSkillRegistry 指向真实目录
        // （已知问题：https://github.com/alibaba/spring-ai-alibaba/issues/4426）
        SkillRegistry skillRegistry = ClasspathSkillRegistry.builder()
                .classpathPath("skills")
                .build();
        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .build();
        // 文件系统写法示例：
        // SkillRegistry skillRegistry = FileSystemSkillRegistry.builder()
        //         .projectSkillsDirectory(Paths.get("src/main/resources/skills").toAbsolutePath().toString())
        //         .build();

        HumanInTheLoopHook hitl = HumanInTheLoopHook.builder()
                .approvalOn("refund_order", ToolConfig.builder()
                        .description("退款将真实动账，请核对订单号与金额后再批准。")
                        .build())
                .build();

        SummarizationHook summarization = SummarizationHook.builder()
                .model(chatModel)
                .maxTokensBeforeSummary(3000)
                .messagesToKeep(16)
                .build();

        ModelCallLimitHook modelLimit = ModelCallLimitHook.builder()
                .runLimit(10)
                .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                .build();

        ToolRetryInterceptor toolRetry = ToolRetryInterceptor.builder()
                .maxRetries(2)
                .onFailure(ToolRetryInterceptor.OnFailureBehavior.RETURN_MESSAGE)
                .build();

        this.agent = ReactAgent.builder()
                .name("cs_agent")
                .model(chatModel)
                .systemPrompt(SYSTEM_PROMPT)
                .tools(queryOrder, refundOrder) // read_skill 由 skillsHook.getTools() 自动合并，无需手写
                .hooks(List.of(skillsHook, summarization, modelLimit, hitl))
                .interceptors(List.of(toolRetry))
                .saver(saver)
                .build();
    }

    /**
     * 按 threadId 缓存中断快照。
     */
    private final Map<String, InterruptionMetadata> pendingByThread = new ConcurrentHashMap<>();

    @Operation(summary = "客服对话：可能中断（退款待审）")
    @PostMapping("/api/s51/chat")
    public Map<String, Object> chat(@RequestBody ChatReq req) throws Exception {
        String threadId = Optional.ofNullable(req.threadId()).orElse("cs-demo-1");
        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("user_id", Optional.ofNullable(req.userId()).orElse("u10086"))
                .build();

        Optional<?> out = agent.invokeAndGetOutput(req.message(), config);
        return toView(threadId, out);
    }

    @Operation(summary = "人工审批后退款：decision=APPROVED|EDITED|REJECTED")
    @PostMapping("/api/s52/resume")
    public Map<String, Object> resume(@RequestBody ResumeReq req) throws Exception {
        InterruptionMetadata pending = pendingByThread.get(req.threadId());
        if (pending == null || pending.toolFeedbacks() == null || pending.toolFeedbacks().isEmpty()) {
            return Map.of("error", "该 threadId 没有待审批中断，请先调 /api/s51/chat 触发退款");
        }

        InterruptionMetadata.Builder feedback = InterruptionMetadata.builder()
                .nodeId(pending.node())
                .state(pending.state());

        for (InterruptionMetadata.ToolFeedback tf : pending.toolFeedbacks()) {
            var b = InterruptionMetadata.ToolFeedback.builder(tf);
            switch (req.decision().toUpperCase(Locale.ROOT)) {
                case "EDITED" -> b.arguments(Optional.ofNullable(req.editedArguments()).orElse(tf.getArguments()))
                        .result(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED);
                case "REJECTED" -> b.result(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED)
                        .description(Optional.ofNullable(req.reason()).orElse("人工拒绝退款"));
                default -> b.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
            }
            feedback.addToolFeedback(b.build());
        }

        RunnableConfig resume = RunnableConfig.builder()
                .threadId(req.threadId())
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedback.build())
                .build();

        Optional<?> out = agent.invokeAndGetOutput("", resume);
        // 恢复后再清掉，避免重复审批
        pendingByThread.remove(req.threadId());
        return toView(req.threadId(), out);
    }

    private Map<String, Object> toView(String threadId, Optional<?> out) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("threadId", threadId);
        if (out.isEmpty()) {
            view.put("status", "empty");
            return view;
        }
        Object v = out.get();
        if (v instanceof InterruptionMetadata interruption) {
            pendingByThread.put(threadId, interruption); // 服务端留底，resume 用
            view.put("status", "interrupted");
            view.put("pending", toPendingView(interruption)); // 显式 DTO，避免 Jackson 丢字段
            return view;
        }
        view.put("status", "completed");
        view.put("output", String.valueOf(v));
        return view;
    }

    /** 手动抽出审批需要的字段，保证 HTTP JSON 里能看到 toolFeedbacks */
    private Map<String, Object> toPendingView(InterruptionMetadata interruption) {
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("node", interruption.node());
        List<Map<String, Object>> feedbacks = new ArrayList<>();
        for (InterruptionMetadata.ToolFeedback tf : interruption.toolFeedbacks()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", tf.getId());
            one.put("name", tf.getName());
            one.put("arguments", tf.getArguments());
            one.put("description", tf.getDescription());
            one.put("result", tf.getResult());
            feedbacks.add(one);
        }
        pending.put("toolFeedbacks", feedbacks);
        return pending;
    }

    public class  QueryOrder implements BiFunction<OrderQuery, ToolContext, String> {

        @Override
        public String apply(OrderQuery orderQuery, ToolContext toolContext) {

            if (queryFailBudget.getAndDecrement() > 0) {
                throw new RuntimeException("调用超时");
            }
            String orderId = orderQuery == null ? "" : orderQuery.orderId();
            if ("O001".equalsIgnoreCase(orderId)) {
                return "{\"orderId\":\"O001\",\"status\":\"paid\",\"amount\":199.0,\"refundable\":true}";
            }
            return "{\"error\":true,\"message\":\"订单不存在\"}";
        }
    }

    private String refundOrder(RefundRequest r, ToolContext ctx) {
        // 真正执行到这里，说明已经过人工批准（或编辑后的参数）
        return "{\"refundId\":\"R-" + r.orderId() + "\",\"amount\":" + r.amount()
                + ",\"status\":\"success\"}";
    }

    public record ChatReq(String message, String threadId, String userId) {
    }

    public record ResumeReq(
            String threadId,
            String decision,
            String editedArguments,
            String reason,
            InterruptionMetadata pending
    ) {
    }

    public static class OrderQuery {
        @ToolParam(description = "订单号，如 O001")
        private String orderId;

        public String orderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }
    }

    public static class RefundRequest {
        @ToolParam(description = "订单号")
        private String orderId;
        @ToolParam(description = "退款金额")
        private double amount;

        public String orderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public double amount() {
            return amount;
        }

        public void setAmount(double amount) {
            this.amount = amount;
        }
    }
}