package space.kmind.zk.a2aClient.consumer;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.a2a.A2aRemoteAgent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * @author zk
 * @date 2026/11/18 11:11
 */
@RestController
@RequestMapping("/api/s91")
public class A2aCallerController {

    private final RemoteAnalysisAgentHolder holder;

    public A2aCallerController(RemoteAnalysisAgentHolder holder) {
        this.holder = holder;
    }

    @PostMapping("/invoke")
    public Map<String, Object> invoke(@RequestBody InvokeReq req) {
        String q = (req.q() == null || req.q().isBlank())
                ? "Q1 营收 100，Q2 营收 120，请做环比简述" : req.q();

        A2aRemoteAgent agent;
        try {
            agent = holder.get();      // 发现失败在这：Nacos 里查不到这个 Agent
        } catch (Exception e) {
            return Map.of("status", "AGENT_NOT_FOUND",
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        try {
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(req.threadId() == null ? "s91-remote-1" : req.threadId())
                    .build();
            Optional<OverAllState> state = agent.invoke(q, config);

            // 远程回答在 outputKey（默认 "output"）下
            String answer = state.map(s -> s.value("output", "")).orElse("");
            return Map.of("status", answer.isBlank() ? "EMPTY" : "OK", "answer", answer);
        } catch (Exception e) {
            // 调用失败在这：B 挂了 / 超时 / 返回错误
            return Map.of("status", "AGENT_UNREACHABLE",
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public record InvokeReq(String q, String threadId) {}
}
