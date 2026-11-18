package space.kmind.zk.a2aClient.consumer;

import com.alibaba.cloud.ai.graph.agent.a2a.A2aRemoteAgent;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
/**
 * @author zk
 * @date 2026/11/18 10:42
 */
/**
 * 懒加载单例：A2aRemoteAgent 全进程一份。
 * build() 时解析 Card 并订阅变更——之后 B 换了地址重新注册，
 * Nacos 会把新 Card 推过来，代理对象内部跟着更新，不需要重建。
 */
@Component
public class RemoteAnalysisAgentHolder {

    private final AgentCardProvider agentCardProvider;

    private volatile A2aRemoteAgent remote;

    public RemoteAnalysisAgentHolder(
            // 存在多个 AgentCardProvider 实现时按名注入，正文 2.2 注意事项第二点
            @Qualifier("nacosAgentCardProvider") AgentCardProvider agentCardProvider) {
        this.agentCardProvider = agentCardProvider;
    }

    public A2aRemoteAgent get() {
        A2aRemoteAgent local = this.remote;
        if (local == null) {
            synchronized (this) {
                if (this.remote == null) {
                    this.remote = build();
                }
                local = this.remote;
            }
        }
        return local;
    }

    private A2aRemoteAgent build() {
        return A2aRemoteAgent.builder()
                .name("data_analysis_agent")      // 与 B 注册的 card.name 一致
                .description("远程数据分析智能体")   // build() 校验要求非空
                .instruction("{input}")
                .shareState(false)   // 在这里设置：false 隔离状态，true 共享状态
                .agentCardProvider(agentCardProvider)
                .build();
    }
}