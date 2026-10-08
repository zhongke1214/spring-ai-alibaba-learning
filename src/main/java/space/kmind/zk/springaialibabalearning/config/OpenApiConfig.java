package space.kmind.zk.springaialibabalearning.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 文档配置
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Spring AI Alibaba Learning API")
                        .description("Spring AI Alibaba 学习项目接口文档")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("zk")
                                .email("")));
    }
}
