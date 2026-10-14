package space.kmind.zk.springaialibabalearning.stage03;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
/**
 * @author zk
 * @date 2026/10/14 15:05
 */

/**
 * 阶段 3：记忆 / Checkpoint / 状态
 * <p>
 */
@Tag(name = "Stage03-记忆与 Checkpoint", description = "持久化续聊 / 状态 / 修剪")
@RestController
public class AMemoryCheckpointDemo {

    private final ReactAgent agent;
    private final BaseCheckpointSaver saver;

    public AMemoryCheckpointDemo(ChatModel chatModel, RedisSaver redisSaver) throws Exception {
        this.saver = redisSaver;

        this.agent = ReactAgent.builder()
                .name("checkpoint_agent")
                .model(chatModel)
                .systemPrompt("""
                        你是会话助手。
                        当用户提到姓名或所在城市时，要记住并在后续问答中使用。
                        回答简洁；不要编造用户没说过的个人信息。
                        """)
                .hooks(new MessageTrimmingHook())
                .saver(redisSaver) // 可换成 MysqlSaver / FileSystemSaver
                .build();
    }

    @Operation(summary = "续聊：同一 threadId 记住上下文（重启后也应可用）")
    @GetMapping("/api/s41/chat")
    public String chat(
            @RequestParam(defaultValue = "我叫张三，在成都") String q,
            @RequestParam(defaultValue = "acme:u10086:c-001") String threadId,
            @RequestParam(defaultValue = "u10086") String userId,
            @RequestParam(defaultValue = "acme") String tenantId) throws Exception {

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("user_id", userId)
                .addMetadata("tenant_id", tenantId)
                .build();

        return agent.call(q, config).getText();
    }

    @Operation(summary = "查看 OverAllState.messages")
    @GetMapping("/api/s42/state")
    public String state(
            @RequestParam(defaultValue = "再确认一下我是谁") String q,
            @RequestParam(defaultValue = "acme:u10086:c-001") String threadId) throws Exception {

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        Optional<OverAllState> opt = agent.invoke(q, config);
        if (opt.isEmpty()) {
            return "empty state";
        }
        List<Message> messages = opt.get().value("messages", List.of());
        return "messageCount=" + messages.size() + "\n" + messages;
    }

    @Operation(summary = "释放会话 Checkpoint（新对话前可调用）")
    @GetMapping("/api/s43/release")
    public String release(@RequestParam(defaultValue = "acme:u10086:c-001") String threadId)
            throws Exception {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        BaseCheckpointSaver.Tag tag = saver.release(config);
        return "released threadId=" + tag.threadId()
                + ", checkpointCount=" + tag.checkpoints().size();
    }

    /**
     * 教学用修剪：消息过多时保留首条 + 最近若干条。
     * 生产请按 token 估算，并避免拆坏 tool 调用对。
     */
    @HookPositions({HookPosition.BEFORE_MODEL})
    static class MessageTrimmingHook extends MessagesModelHook {

        private static final int MAX_MESSAGES = 12;

        @Override
        public String getName() {
            return "message_trimming";
        }

        @Override
        public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
            if (previousMessages.size() <= MAX_MESSAGES) {
                return new AgentCommand(previousMessages);
            }
            Message first = previousMessages.get(0);
            List<Message> recent = previousMessages.subList(
                    previousMessages.size() - (MAX_MESSAGES - 1),
                    previousMessages.size());
            List<Message> trimmed = new ArrayList<>();
            trimmed.add(first);
            trimmed.addAll(recent);
            return new AgentCommand(trimmed, UpdatePolicy.REPLACE);
        }
    }
}
