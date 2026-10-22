package space.kmind.zk.springaialibabalearning.stage05;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LoopAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.loop.LoopMode;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Tag(name = "Stage05-多智能体", description = "Sequential/Parallel/Routing/Loop/Supervisor/混合")
@RestController
public class ATravelMultiAgentDemo {

    private final SequentialAgent sequentialAgent;
    private final ParallelAgent parallelAgent;
    private final LlmRoutingAgent routingAgent;
    private final LoopAgent loopAgent;
    private final ReactAgent supervisorAgent;
    private final SequentialAgent hybridAgent;

    public ATravelMultiAgentDemo(ChatModel chatModel) throws Exception {

        // ========== 模式一：Sequential + outputKey 接力（草稿 → 润色） ==========
        ReactAgent drafter = ReactAgent.builder()
                .name("itinerary_drafter")
                .model(chatModel)
                .description("行程草稿员。只写按天行程初稿。")
                .instruction("根据用户需求写一份按天行程草稿，只返回草稿正文。用户需求：{input}")
                .outputKey("itinerary_draft")
                .build();

        ReactAgent polisher = ReactAgent.builder()
                .name("itinerary_polisher")
                .model(chatModel)
                .description("行程润色员。只润色已有草稿。")
                .instruction("请润色以下行程草稿，使其更清晰，只返回终稿：\n{itinerary_draft}")
                .outputKey("itinerary_final")
                .includeContents(false)   // 润色只看草稿，父上下文是噪音，关掉
                .build();

        this.sequentialAgent = SequentialAgent.builder()
                .name("itinerary_sequential")
                .description("先拟草稿再润色")
                .subAgents(List.of(drafter, polisher))
                .build();

        // ========== 模式二：Parallel 扇出后合并（机票/酒店/天气互不依赖） ==========
        ReactAgent flightP = expert(chatModel, "flight_parallel",
                "机票调研。只回答航班与票价要点。",
                "你是机票调研员。针对用户差旅需求给出航班要点。需求：{input}",
                "flight_plan");
        ReactAgent hotelP = expert(chatModel, "hotel_parallel",
                "酒店调研。只回答住宿要点。",
                "你是酒店调研员。针对用户差旅需求给出住宿要点。需求：{input}",
                "hotel_plan");
        ReactAgent weatherP = expert(chatModel, "weather_parallel",
                "天气调研。只回答目的地天气与穿衣建议。",
                "你是天气顾问。针对用户目的地给出天气与穿衣建议。需求：{input}",
                "weather_plan");

        this.parallelAgent = ParallelAgent.builder()
                .name("travel_parallel")
                .description("并行调研机票、酒店、天气")
                .subAgents(List.of(flightP, hotelP, weatherP))
                .mergeOutputKey("research_bundle")
                .mergeStrategy(new ParallelAgent.DefaultMergeStrategy())
                .build();

        // ========== 模式三：LlmRouting 单次分诊（description 划边界是关键） ==========
        ReactAgent flightR = expert(chatModel, "flight_agent",
                "机票专家。只处理航班、舱位、票价。不处理酒店、天气、行程。",
                "你是机票顾问。给出航班方案要点。用户问题：{input}",
                "flight_plan");
        ReactAgent hotelR = expert(chatModel, "hotel_agent",
                "酒店专家。只处理住宿、区位、价格。不处理机票、天气、行程。",
                "你是酒店顾问。给出住宿建议要点。用户问题：{input}",
                "hotel_plan");
        ReactAgent itineraryR = expert(chatModel, "itinerary_agent",
                "行程专家。只处理景点与日程安排。不处理订票订房。",
                "你是行程规划师。给出按天行程。用户问题：{input}",
                "itinerary_plan");
        // 天气专家特意关掉父上下文：防止被用户"顺带提一句机票"带偏
        ReactAgent weatherR = ReactAgent.builder()
                .name("weather_agent")
                .model(chatModel)
                .description("天气专家。只处理目的地天气与穿衣。不处理机票酒店行程。")
                .instruction("你是天气顾问。只根据用户问题给天气与穿衣建议。用户问题：{input}")
                .outputKey("weather_plan")
                .includeContents(false)
                .build();

        this.routingAgent = LlmRoutingAgent.builder()
                .name("travel_router")
                .description("差旅意图分诊")
                .model(chatModel)
                .systemPrompt("""
                        你是差旅分诊台。只返回下列名称之一：
                        flight_agent / hotel_agent / itinerary_agent / weather_agent
                        机票→flight_agent；酒店→hotel_agent；行程景点→itinerary_agent；天气穿衣→weather_agent。
                        """)
                .subAgents(List.of(flightR, hotelR, itineraryR, weatherR))
                .build();

        // ========== 模式四：Loop 有限次打磨 ==========
        ReactAgent refiner = ReactAgent.builder()
                .name("itinerary_refiner")
                .model(chatModel)
                .description("行程打磨员。每次把行程写得更具体一点。")
                .instruction("""
                        在现有思路上改进杭州行程，让时间安排更具体。
                        用户原需求：{input}
                        只返回改进后的完整行程。
                        """)
                .outputKey("refined_itinerary")
                .build();

        this.loopAgent = LoopAgent.builder()
                .name("itinerary_loop")
                .description("最多打磨 3 次")
                .subAgent(refiner)
                .loopStrategy(LoopMode.count(3))   // 必须设上限，防 Token 焚烧
                .build();

        // ========== 模式五：Supervisor = ReactAgent + AgentTool ==========
        ReactAgent flightTool = toolExpert(chatModel, "flight_agent",
                "机票专家。根据自然语言给出航班方案要点。",
                "你是机票顾问。根据输入给出航班方案要点，只返回要点。");
        ReactAgent hotelTool = toolExpert(chatModel, "hotel_agent",
                "酒店专家。根据自然语言给出住宿建议。",
                "你是酒店顾问。根据输入给出住宿建议要点，只返回要点。");
        ReactAgent itineraryTool = toolExpert(chatModel, "itinerary_agent",
                "行程专家。根据自然语言给出按天行程草稿。",
                "你是行程规划师。根据输入给出按天行程草稿，只返回草稿。");

        this.supervisorAgent = ReactAgent.builder()
                .name("travel_supervisor")
                .model(chatModel)
                .systemPrompt("""
                        你是差旅总控。可调用 flight_agent、hotel_agent、itinerary_agent。
                        综合需求时分步调用相关工具，最后汇总成给用户的完整说明。
                        只需单一领域时，只调对应一个工具即可。
                        """)
                .tools(
                        AgentTool.getFunctionToolCallback(flightTool),
                        AgentTool.getFunctionToolCallback(hotelTool),
                        AgentTool.getFunctionToolCallback(itineraryTool))
                .saver(new MemorySaver())
                .build();

        // ========== 模式六：混合嵌套 Sequential( Parallel → Summarizer ) ==========
        ReactAgent hybridSummarizer = ReactAgent.builder()
                .name("travel_summarizer")
                .model(chatModel)
                .description("差旅简报员。把并行调研结果整理成简报。")
                .instruction("""
                        根据下列并行调研材料，写一份给领导看的简洁中文简报（保留关键结论）：
                        {research_bundle}
                        """)
                .outputKey("final_brief")
                .includeContents(false)
                .build();

        // 注意这里直接复用了上面的 parallelAgent 作为外层 Sequential 的一个子步骤——嵌套就是这么自然
        this.hybridAgent = SequentialAgent.builder()
                .name("travel_hybrid")
                .description("先并行调研，再串行汇总")
                .subAgents(List.of(parallelAgent, hybridSummarizer))
                .build();
    }

    // 流水线用：有 outputKey、instruction 里带 {input} 占位符
    private static ReactAgent expert(ChatModel model, String name, String desc,
                                     String instruction, String outputKey) {
        return ReactAgent.builder()
                .name(name)
                .model(model)
                .description(desc)
                .instruction(instruction)
                .outputKey(outputKey)
                .build();
    }

    // Supervisor 用：没有 outputKey，靠 systemPrompt + inputType(String) 接收子任务、工具返回值回传
    private static ReactAgent toolExpert(ChatModel model, String name,
                                         String desc, String systemPrompt) {
        return ReactAgent.builder()
                .name(name)
                .model(model)
                .description(desc)
                .systemPrompt(systemPrompt)
                .inputType(String.class)
                .build();
    }

    @Operation(summary = "Sequential：草稿→润色（outputKey 接力）")
    @PostMapping("/api/s51/sequential")
    public Map<String, Object> sequential(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "杭州两日游，偏西湖与美食");
        return toView(sequentialAgent.invoke(q),
                List.of("itinerary_draft", "itinerary_final"));
    }

    @Operation(summary = "Parallel：机票+酒店+天气并行合并")
    @PostMapping("/api/s52/parallel")
    public Map<String, Object> parallel(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "下周五去杭州两天，预算中等");
        return toView(parallelAgent.invoke(q),
                List.of("research_bundle", "flight_plan", "hotel_plan", "weather_plan"));
    }

    @Operation(summary = "Routing：单意图分诊")
    @PostMapping("/api/s53/route")
    public Map<String, Object> route(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "下周五上海飞杭州机票怎么选？");
        return toView(routingAgent.invoke(q),
                List.of("flight_plan", "hotel_plan", "itinerary_plan", "weather_plan"));
    }

    @Operation(summary = "Loop：行程最多打磨 3 次")
    @PostMapping("/api/s54/loop")
    public Map<String, Object> loop(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "杭州两日游行程，请尽量具体到半天");
        return toView(loopAgent.invoke(q), List.of("refined_itinerary"));
    }

    @Operation(summary = "Supervisor：专家当 Tool 多轮调度")
    @PostMapping("/api/s55/supervise")
    public Map<String, Object> supervise(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "杭州两日游，帮我安排机票思路、住宿和行程");
        return toView(supervisorAgent.invoke(q), List.of());
    }

    @Operation(summary = "混合：Parallel 调研后再 Sequential 汇总")
    @PostMapping("/api/s56/hybrid")
    public Map<String, Object> hybrid(@RequestBody Map<String, String> body) throws Exception {
        String q = body.getOrDefault("message", "杭州两日：并行调研机票酒店天气并出简报");
        return toView(hybridAgent.invoke(q),
                List.of("research_bundle", "final_brief"));
    }

    private Map<String, Object> toView(Optional<OverAllState> opt, List<String> keys) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (opt.isEmpty()) {
            view.put("status", "empty");
            return view;
        }
        OverAllState state = opt.get();
        view.put("status", "ok");
        for (String key : keys) {
            state.value(key).ifPresent(v -> view.put(key, unwrap(v)));
        }
        // Supervisor 没有配 outputKey，最终回复藏在 messages 列表的最后一条里
        state.value("messages").ifPresent(msgs -> {
            if (msgs instanceof List<?> list && !list.isEmpty()) {
                Object last = list.get(list.size() - 1);
                if (last instanceof AssistantMessage m) {
                    view.put("answer", m.getText());
                } else if (last instanceof Message m) {
                    view.put("answer", m.getText());
                }
            }
        });
        return view;
    }

    private Object unwrap(Object v) {
        if (v instanceof AssistantMessage m) {
            return m.getText();
        }
        return String.valueOf(v);
    }
}