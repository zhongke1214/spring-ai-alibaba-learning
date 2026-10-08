package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * @author zk
 * @date 2026/9/30 14:28
 */
@Tag(name = "Stage01-1.5 Chat Memory", description = "大模型会话记忆")
@RestController
public class EMemoryDemo {

    /** 手动管理会话记忆：默认窗口实现，生产可换成 Redis/JDBC 的 ChatMemory */
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(20) // 只留最近 20 条消息，超出丢最旧的
            .build();

    private final ChatClient chatClient;

    public EMemoryDemo(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/api/s15/chat")
    public String chat(@RequestParam(defaultValue = "我是xxx") String q,
                       @RequestParam(defaultValue = "c-001") String conversationId) {

        // 1. 取出该会话已有的历史消息（没有则返回空 List）
        List<Message> history = chatMemory.get(conversationId);

        // 2. 历史 + 本轮用户输入，组装成本次请求的完整消息列表
        List<Message> messages = new ArrayList<>(history);
        messages.add(new UserMessage(q));

        // 3. 调模型
        String answer = chatClient.prompt()
                .messages(messages)
                .call()
                .content();

        // 4. 手动把本轮 user + assistant 两条消息写回记忆
        //    下次同一个 conversationId 进来就能"续聊"
        chatMemory.add(conversationId, List.of(
                new UserMessage(q),
                new AssistantMessage(answer)
        ));

        return answer;
    }

    /** 清空某个会话的记忆，方便演示 */
    @GetMapping("/api/s15/clear")
    public String clear(@RequestParam(defaultValue = "c-001") String conversationId) {
        chatMemory.clear(conversationId);
        return "cleared: " + conversationId;
    }
}