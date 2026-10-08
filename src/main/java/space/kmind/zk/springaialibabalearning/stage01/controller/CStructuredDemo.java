package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stage01-1.3 结构化输出", description = "结构化输出")

@RestController
public class CStructuredDemo {

    private final ChatClient chatClient;

    public CStructuredDemo(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /** 业务对象：用 record 最省事；也可用普通 POJO */
    public record MovieRec(
            String title,
            String reason,
            int year
    ) {}

    /** 推荐写法：entity() 内部用 BeanOutputConverter */
    @GetMapping("/api/s13/entity")
    public MovieRec entity(@RequestParam(defaultValue = "科幻") String genre) {
        return chatClient.prompt()
                .user(u -> u.text("推荐1部经典{genre}电影，理由一句话。")
                        .param("genre", genre))
                .call()
                .entity(MovieRec.class); // 失败时会抛转换异常，调用方自行 catch/重试
    }

    /** 显式 Converter：想自己拼 prompt、或流式聚合后再转时用 */
    @GetMapping("/api/s13/converter")
    public MovieRec converter(@RequestParam(defaultValue = "动画") String genre) {
        BeanOutputConverter<MovieRec> converter = new BeanOutputConverter<>(MovieRec.class);
        // getFormat() 是一段「请按此 JSON Schema 输出」的说明文字
        String text = chatClient.prompt()
                .user("推荐1部" + genre + "电影。\n" + converter.getFormat())
                .call()
                .content();
        return converter.convert(text); // 把模型文本解析成 MovieRec
    }
}