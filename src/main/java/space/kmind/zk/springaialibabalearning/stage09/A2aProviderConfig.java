package space.kmind.zk.springaialibabalearning.stage09;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author zk
 * @date 2026/11/18 9:59
 */
/**
 * 提供者：一个再普通不过的 ReactAgent。
 * 它不知道自己会被远程调用——A2A 的接入全部发生在 yml 和自动配置里。
 */
@Configuration
public class A2aProviderConfig {

    /**
     * 约束一：name 必须与 spring.ai.alibaba.a2a.server.card.name 逐字符一致。
     * 约束二：整个进程只能有一个 Agent Bean——
     *        自动配置按类型注入单个 Agent 来生成名片和执行器，
     *        多一个就 NoUniqueBeanDefinitionException，启动即失败。
     *        要暴露多个 Agent，请起多个实例（见本节末），不要塞进同一个进程。
     */
    @Bean
    public ReactAgent dataAnalysisAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("data_analysis_agent")
                .model(chatModel)
                .description("专门用于数据分析和统计计算的智能体")
                .instruction("""
                        你是数据分析专家。用户会给出一组数字，你做同比/环比等简明分析。
                        只基于给定数字作答，缺的数据明确说缺，禁止编造。
                        """)
                .build();
    }
}