package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 1.2 消息与提示词（Message / Prompt / System）
 * <p>
 * ChatClient 链式调用最终仍会组装成 Prompt，再交给 ChatModel。
 * 底层大致：system→SystemMessage、user→UserMessage、历史消息(Advisor)、Options → Prompt → ChatResponse → 文本。
 * 排查「不记得上下文 / 格式不对 / 系统提示不生效」时，先看消息列表和 Prompt 怎么拼的。
 */
@Tag(name = "Stage01-1.2 消息与提示词", description = "System / User / Prompt 常用案例")
@RestController
public class BPromptDemo {

    private final ChatClient chatClient;

    public BPromptDemo(ChatClient.Builder builder) {
        // defaultSystem：整应用一致人设，设一次即可；单次 .system(...) 会整段覆盖它（不是追加）
        // System 提示宜短：角色一句、边界一句、输出格式一句
        this.chatClient = builder
                .defaultSystem("""
                        你是 Java 学习助手。
                        只回答 Java / Spring 相关问题，其余礼貌拒绝。
                        用简洁中文，必要时给短代码。
                        """)
                .build();
    }

    /** 只用全局人设：不写 .system()，走 defaultSystem */
    @Operation(summary = "defaultSystem：全局人设")
    @GetMapping("/api/s12/chat")
    public String chat(
            @Parameter(description = "用户问题") @RequestParam(defaultValue = "什么是依赖注入？一句话") String q) {
        // user → UserMessage；无 .system() 时用构造时的 defaultSystem
        return chatClient.prompt()
                .user(q)
                .call()
                .content();
    }

    /** 单次覆盖：这次调用换成「面试官」，不影响其它接口的 defaultSystem */
    @Operation(summary = "system：单次覆盖人设（优先于 defaultSystem）")
    @GetMapping("/api/s12/interview")
    public String interview(
            @Parameter(description = "考察主题") @RequestParam(defaultValue = "HashMap") String topic) {
        // 优先级：本次 .system(...) > 全局 defaultSystem（覆盖，不是拼在后面）
        return chatClient.prompt()
                .system("你是 Java 面试官。只出 1 道简答题，不要给答案。")
                .user("请围绕「" + topic + "」出题。")
                .call()
                .content();
    }

    /** 手搓 Message 列表：看清 Prompt 里 system / user / assistant 的结构 */
    @Operation(summary = "手搓 Message → Prompt（理解底层结构）")
    @GetMapping("/api/s12/messages")
    public String rawMessages() {
        List<Message> messages = List.of(
                new SystemMessage("你是校对员，只指出错别字，不要改写文风。"),
                new UserMessage("请检查：春眠不觉晓，处处蚊叮咬。"),
                new AssistantMessage("「蚊叮咬」疑似笔误，常见写法是「闻啼鸟」。"),
                new UserMessage("再检查：夜来风雨声，花落知多少。")
        );
        // 等价于 ChatClient 帮你自动组装 Prompt；多轮/few-shot 时历史就是这样排的
        return chatClient.prompt(new Prompt(messages))
                .call()
                .content();
    }

    /** 模板变量：别用 + 硬拼 Prompt */
    @Operation(summary = "PromptTemplate 变量替换")
    @GetMapping("/api/s12/template")
    public String template(@RequestParam(defaultValue = "成都") String city) {
        PromptTemplate template = new PromptTemplate(
                "用不超过30字介绍{city}，面向第一次去旅游的人。");
        Prompt prompt = template.create(Map.of("city", city));
        return chatClient.prompt(prompt).call().content();
    }
}
