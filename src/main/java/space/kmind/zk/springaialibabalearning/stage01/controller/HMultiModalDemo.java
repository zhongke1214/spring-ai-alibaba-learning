package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.UrlResource;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = "Stage01-1.8 多模态基础", description = "多模态")
@RestController
public class HMultiModalDemo {
    private final ChatClient chatClient;

    public HMultiModalDemo(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 图片 URL 需公网可访问（或换成 classpath 文件资源）。
     * 模型侧请使用支持视觉的通义模型，具体模型名以百炼为准。
     */
    @GetMapping("/api/s18/image")
    public String describe(@RequestParam(defaultValue = "用中文描述图片里有什么，不超过50字") String q)
            throws Exception {

        Media image = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG) // jpeg 就换 IMAGE_JPEG
                //网络地址
//                .data(new UrlResource(URI.create(imageUrl)))
                //本地图片
                .data(new ClassPathResource("doc/images/img.png"))
                .build();

        return chatClient.prompt()
                .user(u -> u.text(q)
                        .media(image)) // 文本和图片一起作为 User 输入
                .call()
                .content();
    }
}