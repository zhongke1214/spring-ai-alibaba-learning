package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * @author zk
 * @date 2026/9/28 17:41
 */
@Tag(name = "Stage01-1.1 模型抽象", description = "ChatModel、ChatClient、ChatOptions")
@RestController
public class AModelClientDemo {

    private final ChatModel chatModel;
    private final ChatClient chatClient;


    public AModelClientDemo(ChatModel chatModel, ChatClient.Builder builder) {
        this.chatModel = chatModel;
        // Builder 由 starter 自动配置；这里做出一个可复用的 ChatClient
        this.chatClient = builder.build();
    }

    //    1.1.1 ChatModel / StreamingChatModel
    @Operation(summary = "ChatModel 直接调用")
    @GetMapping("/api/s11/model")
    public String byModel(
            @Parameter(description = "用户问题") @RequestParam(defaultValue = "用一句话介绍成都") String q) {
        ChatResponse response = chatModel.call(new Prompt(q));
        // 取第一条候选的文本；一般就一条
        return response.getResult().getOutput().getText();
    }

    //    1.1.2 ChatClient 流式 API（call / stream）

    @Operation(summary = "ChatClient 同步调用")
    @GetMapping("/api/s11/call")
    public String byClient(
            @Parameter(description = "用户问题") @RequestParam(defaultValue = "把春眠不觉晓翻译成英文") String q) {
        return chatClient.prompt()
                .user(q)
                .call()
                .content(); // 只要字符串时用 content()
    }


    /**
     * 流式：前端用 SSE / WebFlux 接 Flux<String>
     *
     * @param q
     * @return
     */
    @Operation(summary = "ChatClient 流式输出")
    @GetMapping(value = "/api/s11/stream", produces = "text/plain;charset=UTF-8")
    public Flux<String> stream(
            @Parameter(description = "用户问题") @RequestParam(defaultValue = "讲个50字的冷笑话") String q) {
        return chatClient.prompt()
                .user(q)
                .stream() //swagger可能看不到流式输出效果，可以在浏览器F12查看响应或者用支持的前端界面测试
                .content(); // 每个元素是一小段增量文本
    }


// 1.1.3 ChatOptions

    /**
     * 临时改选项：低温更稳，适合分类/抽取；高温更活，适合文案
     */
    @Operation(summary = "带 temperature / maxTokens 选项调用,其余参数可自行添加测试")
    @GetMapping("/api/s11/options")
    public String withOptions(
            @Parameter(description = "用户问题") @RequestParam(defaultValue = "给咖啡店起10个中文名，逗号分隔") String q) {
        return chatClient.prompt()
                .user(q)
                .options(ChatOptions.builder()
                        .temperature(0.2)  // 0~1，越低越保守
                        .maxTokens(15)    // 限制生成长度，省钱也防废话
                        .build())
                .call()
                .content();
    }
}
