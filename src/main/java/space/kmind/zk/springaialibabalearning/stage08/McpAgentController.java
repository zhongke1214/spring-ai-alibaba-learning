//package space.kmind.zk.springaialibabalearning.stage08;
//
//import com.alibaba.cloud.ai.graph.OverAllState;
//import com.alibaba.cloud.ai.graph.RunnableConfig;
//import com.alibaba.cloud.ai.graph.agent.ReactAgent;
//import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
//import io.modelcontextprotocol.client.McpSyncClient;
//import io.modelcontextprotocol.spec.McpSchema;
//import io.swagger.v3.oas.annotations.Operation;
//import io.swagger.v3.oas.annotations.tags.Tag;
//import org.springframework.ai.chat.messages.AssistantMessage;
//import org.springframework.ai.chat.messages.Message;
//import org.springframework.ai.chat.model.ChatModel;
//import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//import java.util.stream.Collectors;
//
///**
// * @author zk
// * @date 2026/11/11 16:57
// */
//@Tag(name = "Stage08-MCP", description = "协议基础ClientServer与ReactAgent接入")
//@RestController
//public class McpAgentController {
//
//    private final ReactAgent agent;
//    private final ChatModel chatModel;
//
//    public McpAgentController(ChatModel chatModel, SyncMcpToolCallbackProvider mcpTools) {
//        this.chatModel = chatModel;
//        this.agent = ReactAgent.builder()
//                .name("mcp_react_agent")
//                .model(chatModel)
//                .systemPrompt("""
//                        你是运维助手。查时间用 get_current_time，读文件用 read_text_file。
//                        工具返回以 ERROR 开头时，如实转述错误，禁止编造文件内容。
//                        """)
//                // ToolCallbackProvider 由 agent-framework 适配成可执行工具（正文 3.1 那条链路）
//                .toolCallbackProviders(mcpTools)
//                .saver(new MemorySaver())
//                .build();
//    }
//
//    @Operation(summary = "测试调用McpTool服务")
//    @PostMapping("/api/s81/chat")
//    public Map<String, Object> chat(@RequestBody ChatReq req) throws Exception {
//        RunnableConfig config = RunnableConfig.builder()
//                .threadId(req.threadId() == null ? "mcp-1" : req.threadId())
//                .build();
//
//        Optional<OverAllState> out = agent.invoke(req.q(), config);
//        if (out.isEmpty()) {
//            return Map.of("status", "empty");
//        }
//
//        List<Message> messages = out.get().value("messages", new ArrayList<>());
//
//        // 最终回答 = 最后一条不带 toolCalls 的 AssistantMessage
//        String answer = "";
//        for (Message m : messages) {
//            if (m instanceof AssistantMessage am && !am.hasToolCalls()) {
//                answer = am.getText();
//            }
//        }
//
//        // 实际调了哪些工具 —— 验证 MCP 链路最直接的证据
//        List<String> toolsUsed = messages.stream()
//                .filter(AssistantMessage.class::isInstance)
//                .map(AssistantMessage.class::cast)
//                .flatMap(am -> am.getToolCalls().stream())
//                .map(AssistantMessage.ToolCall::name)
//                .toList();
//
//        return Map.of("answer", answer, "toolsUsed", toolsUsed);
//    }
//
//    public record ChatReq(String q, String threadId) {
//    }
//
//
//@Autowired
//private List<McpSyncClient> mcpClients;
//
//@GetMapping("/api/s81/resource")
//@Operation(summary = "测试调用McpResource服务")
//public Map<String, Object> readResource(@RequestParam(defaultValue = "hello.txt") String name) {
//    McpSyncClient client = mcpClients.get(0);
//
//    // ② 发现
//    List<String> templates = client.listResourceTemplates().resourceTemplates().stream()
//            .map(t -> t.uriTemplate() + " （" + t.description() + "）")
//            .toList();
//
//    // ③ 读取
//    var result = client.readResource(new McpSchema.ReadResourceRequest("file:///mcp-data/" + name));
//
//    // contents 是 ResourceContents 的列表，文本文件就是 TextResourceContents
//    String text = result.contents().stream()
//            .map(c -> ((McpSchema.TextResourceContents) c).text())
//            .collect(Collectors.joining());
//
//    // ④ 之后的每一步都是你的代码：拼进 systemPrompt、丢给 chatModel 总结、或者直接忽略
//    return Map.of("templates", templates, "text", text);
//}
//
//
//    @GetMapping("/api/s81/prompt/daily-report")
//    @Operation(summary = "测试McpPrompt")
//    public Map<String, Object> dailyReport(@RequestParam String topic) {
//        McpSyncClient client = mcpClients.get(0);
//
//        // ① 发现：前端「模板选择」下拉框的数据就来自这里
//        List<String> prompts = client.listPrompts().prompts().stream()
//                .map(p -> p.name() + " （" + p.description() + "）")
//                .toList();
//
//        // ② 取模板：arguments 的 key 要和 @McpArg(name = "topic") 对上
//        var result = client.getPrompt(new McpSchema.GetPromptRequest("daily-report", Map.of("topic", topic)));
//
//        // ③ 返回的是渲染好的消息列表，取出文本
//        String rendered = result.messages().stream()
//                .map(m -> ((McpSchema.TextContent) m.content()).text())
//                .collect(Collectors.joining("\n"));
//
//        // ④ 谁调模型、用哪个模型，Host 决定
//        String answer = chatModel.call(rendered);
//        return Map.of("prompts", prompts, "answer", answer);
//    }
//}