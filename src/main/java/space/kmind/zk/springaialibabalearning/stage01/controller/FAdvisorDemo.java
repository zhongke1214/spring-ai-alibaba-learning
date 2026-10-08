package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Advisors 讲解示例
 *
 * 本示例通过自定义 Advisor + 不同 order，直观展示"洋葱模型"：
 *
 *   请求方向（order 从小到大）：
 *     MyLoggerAdvisor(0) -> MyAuditAdvisor(10) -> SafeGuardAdvisor(20) -> MessageMemory(30) -> 模型
 *
 *   响应方向（order 从大到小）：
 *     模型 -> MessageMemory(30) -> SafeGuardAdvisor(20) -> MyAuditAdvisor(10) -> MyLoggerAdvisor(0)
 *
 * 控制台会看到成对出现的 ">>>" 和 "<<<" 日志，且顺序正好相反。
 */
@Tag(name = "Stage01-1.6 Advisors", description = "Advisors拦截器")
@RestController
public class FAdvisorDemo {

    private final ChatClient chatClient;

    public FAdvisorDemo(ChatClient.Builder builder) {

        // ---------- 1. 对话记忆 ----------
        ChatMemory memory = MessageWindowChatMemory.builder().build();

        // ---------- 2. 自定义日志 Advisor（order=0，最先进入、最后退出） ----------
        //SimpleLoggerAdvisor 默认使用 DEBUG 级别 输出日志，而 Spring Boot 默认只显示 INFO 及以上级别,自定义 LoggerAdvisor,用 INFO 级别 输出
        MyLoggerAdvisor loggerAdvisor = new MyLoggerAdvisor(0);

        // ---------- 3. 自定义审计 Advisor（order=10） ----------
        MyAuditAdvisor auditAdvisor = new MyAuditAdvisor(10);

        // ---------- 4. 安全护栏（order=20） ----------
        SafeGuardAdvisor safeGuardAdvisor = SafeGuardAdvisor.builder()
                .sensitiveWords(List.of("暴力", "违禁词", "敏感内容"))
                .failureResponse("抱歉，您的问题包含敏感内容，我无法回答。")
                .order(20)// 显式指定：order为20
                .build();

        // ---------- 5. 消息记忆 Advisor（order=30，最后进入、最先退出） ----------
        MessageChatMemoryAdvisor messageMemoryAdvisor =
                MessageChatMemoryAdvisor
                        .builder(memory)
                        .order(30)// 显式指定：order为30
                        .build();

        // ---------- 6. 组装默认链 ----------
        // 显式指定 order，让洋葱模型的层次更清晰。
        this.chatClient = builder
                .defaultAdvisors(
                        loggerAdvisor,          // order=0  最外层
                        auditAdvisor,           // order=10
                        safeGuardAdvisor,       // order=20
                        messageMemoryAdvisor    // order=30 最内层
                )
                .build();
    }

    /**
     * 基础对话接口。
     * 访问示例：/api/s16/chat?q=你好
     */
    @GetMapping("/api/s16/chat")
    public String chat(@RequestParam String q,
                       @RequestParam(defaultValue = "adv-1") String conversationId) {
        return chatClient.prompt()
                .user(q)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    /**
     * 演示 SafeGuardAdvisor 触发敏感词拦截。
     * 观察日志：命中敏感词后，响应会从 SafeGuardAdvisor 层直接往回走，
     * 后面的 MessageChatMemoryAdvisor 不会再执行。
     * 访问示例：/api/s16/chat-guard?q=暴力内容
     */
    @GetMapping("/api/s16/chat-guard")
    public String chatWithGuard(@RequestParam String q,
                                @RequestParam(defaultValue = "adv-1") String conversationId) {
        return chatClient.prompt()
                .user(q)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}

/**
 * 自定义日志 Advisor。
 *
 * 特点：
 * - 使用 INFO 级别，无需修改 yml 日志配置就能看到；
 * - 通过 ">>>" 和 "<<<" 前缀直观展示"进入请求 / 退出响应"的洋葱模型；
 * - 打印用户输入、模型输出以及当前 Advisor 的执行顺序。
 */
 class MyLoggerAdvisor implements BaseAdvisor {

     private static final Logger log = LoggerFactory.getLogger(MyLoggerAdvisor.class);

     /**
      * 执行顺序，值越小越先执行
      */
     private final int order;

     public MyLoggerAdvisor() {
         this(0);
     }

     public MyLoggerAdvisor(int order) {
         this.order = order;
     }

     @Override
     public String getName() {
         return "MyLoggerAdvisor";
     }

     @Override
     public int getOrder() {
         return this.order;
     }

     /**
      * 请求阶段：正向执行，order 越小越先被调用。
      */
     @Override
     public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
         String userText = request.prompt().getUserMessage().getText();
         log.info(">>> [{}] 进入请求阶段 | order={} | 用户输入：{}",
                 getName(), getOrder(), userText);
         return request;
     }

     /**
      * 响应阶段：反向执行，order 越小越后被调用。
      */
     @Override
     public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
         String text = response.chatResponse().getResult().getOutput().getText();
         log.info("<<< [{}] 进入响应阶段 | order={} | 模型输出：{}",
                 getName(), getOrder(), text);
         return response;
     }
 }

/**
 * 自定义审计 Advisor。
 *
 * 用来和 MyLoggerAdvisor 搭配，展示 Advisor 链的多层"洋葱"结构：
 * 请求阶段按 order 从小到大依次进入，
 * 响应阶段按 order 从大到小依次退出。
 */
 class MyAuditAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MyAuditAdvisor.class);

    private final int order;

    public MyAuditAdvisor(int order) {
        this.order = order;
    }

    @Override
    public String getName() {
        return "MyAuditAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        log.info(">>> [{}] 进入请求阶段 | order={} | 记录审计信息",
                getName(), getOrder());
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        log.info("<<< [{}] 进入响应阶段 | order={} | 完成审计",
                getName(), getOrder());
        return response;
    }
}
