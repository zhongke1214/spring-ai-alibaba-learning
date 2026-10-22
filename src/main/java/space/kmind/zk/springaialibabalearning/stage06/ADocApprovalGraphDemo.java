package space.kmind.zk.springaialibabalearning.stage06;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 文档审批流程 StateGraph 示例（含并行检查 + 人工卡点）
 * 适配 Spring AI Alibaba 1.1.2.2
 */
@Tag(name = "Stage06-文档审批 Graph", description = "StateGraph 并行检查 + 人工审批流实战")
@RestController
public class ADocApprovalGraphDemo {

    // ========== 节点名：集中定义，避免字符串散落各处拼错（调试第一大坑）==========
    private static final String NODE_INGEST = "ingest";
    private static final String NODE_CLASSIFY = "classify";
    private static final String NODE_SENSITIVE_CHECK = "sensitive_check";
    private static final String NODE_REQUIRED_CHECK = "required_field_check";
    private static final String NODE_COMPLIANCE_MERGE = "compliance_merge";
    private static final String NODE_HUMAN_REVIEW = "human_review";
    private static final String NODE_APPROVE = "approve";
    private static final String NODE_REJECT = "reject";

    // ========== 状态 key ==========
    private static final String K_DOC_TEXT = "doc_text";
    private static final String K_DOC_TYPE = "doc_type";
    private static final String K_SENSITIVE_OK = "sensitive_ok";
    private static final String K_SENSITIVE_NOTE = "sensitive_note";
    private static final String K_REQUIRED_OK = "required_ok";
    private static final String K_REQUIRED_NOTE = "required_note";
    private static final String K_COMPLIANCE_OK = "compliance_ok";
    private static final String K_COMPLIANCE_NOTE = "compliance_note";
    private static final String K_APPROVED = "approved";
    private static final String K_REVIEW_COMMENT = "review_comment";
    private static final String K_FINAL_STATUS = "final_status";
    private static final String K_NEXT_NODE = "next_node";
    private static final String K_REVIEW_TRAIL = "review_trail";

    // ========== 业务常量 ==========
    private static final Set<String> DOC_TYPES = Set.of("policy", "contract", "other");
    private static final List<String> SENSITIVE_WORDS = List.of("违禁", "保密协议外传", "内部绝密");
    private static final int MIN_DOC_LENGTH = 20;
    /** 已终结状态：这些状态下不允许再次审批，避免重复放行 */
    private static final Set<String> TERMINAL_STATUS = Set.of("approved", "rejected");

    private final StateGraph stateGraph;
    private final CompiledGraph compiledGraph;
    private final ChatClient chatClient;

    public ADocApprovalGraphDemo(ChatModel chatModel) throws GraphStateException {
        this.chatClient = ChatClient.builder(chatModel).build();

        // ========== 1. 状态 key 的合并策略 ==========
        // 标量用 Replace；审批轨迹用 Append（每次追加一条，保留完整留痕）
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> strategyMap = new HashMap<>();
            strategyMap.put(K_DOC_TEXT, new ReplaceStrategy());
            strategyMap.put(K_DOC_TYPE, new ReplaceStrategy());
            strategyMap.put(K_SENSITIVE_OK, new ReplaceStrategy());
            strategyMap.put(K_SENSITIVE_NOTE, new ReplaceStrategy());
            strategyMap.put(K_REQUIRED_OK, new ReplaceStrategy());
            strategyMap.put(K_REQUIRED_NOTE, new ReplaceStrategy());
            strategyMap.put(K_COMPLIANCE_OK, new ReplaceStrategy());
            strategyMap.put(K_COMPLIANCE_NOTE, new ReplaceStrategy());
            strategyMap.put(K_APPROVED, new ReplaceStrategy());
            strategyMap.put(K_REVIEW_COMMENT, new ReplaceStrategy());
            strategyMap.put(K_FINAL_STATUS, new ReplaceStrategy());
            strategyMap.put(K_NEXT_NODE, new ReplaceStrategy());
            strategyMap.put(K_REVIEW_TRAIL, new AppendStrategy());
            return strategyMap;
        };

        // ========== 2. 定义各个节点 ==========
        // 节点1：文档接入，清洗文本
        NodeAction ingestNode = state -> Map.of(
                K_DOC_TEXT, state.value(K_DOC_TEXT, "").toString().trim(),
                K_NEXT_NODE, NODE_CLASSIFY,
                K_REVIEW_TRAIL, List.of("ingest: 文档已接收")
        );

        // 节点2：LLM 文档分类（输出需归一化 + 兜底，不能把模型原话直接当枚举用）
        NodeAction classifyNode = state -> {
            String text = state.value(K_DOC_TEXT, "").toString();
            String docType = classify(text);
            return Map.of(
                    K_DOC_TYPE, docType,
                    K_NEXT_NODE, NODE_COMPLIANCE_MERGE,
                    K_REVIEW_TRAIL, List.of("classify: doc_type=" + docType)
            );
        };

        // 节点3-A：敏感词检查（并行分支一，只写 sensitive_* 这两个专属 key）
        NodeAction sensitiveNode = state -> {
            String text = state.value(K_DOC_TEXT, "").toString();
            List<String> hits = SENSITIVE_WORDS.stream().filter(text::contains).toList();
            boolean ok = hits.isEmpty();
            return Map.of(
                    K_SENSITIVE_OK, ok,
                    K_SENSITIVE_NOTE, ok ? "未命中敏感词" : "命中敏感词：" + String.join("、", hits)
            );
        };

        // 节点3-B：必填项/结构完整性检查（并行分支二，只写 required_*，避免与分支一互相覆盖）
        NodeAction requiredNode = state -> {
            String text = state.value(K_DOC_TEXT, "").toString();
            boolean lengthOk = text.length() >= MIN_DOC_LENGTH;
            boolean structured = text.contains("。") || text.contains("\n");
            boolean ok = lengthOk && structured;
            String note = ok ? "必填项与结构完整"
                    : "文档不完整（字数<" + MIN_DOC_LENGTH + " 或缺少段落结构）";
            return Map.of(
                    K_REQUIRED_OK, ok,
                    K_REQUIRED_NOTE, note
            );
        };

        // 节点4：汇合节点。两个并行分支都跑完才会执行到这里
        NodeAction complianceMergeNode = state -> {
            boolean sensitiveOk = Boolean.TRUE.equals(state.value(K_SENSITIVE_OK, false));
            boolean requiredOk = Boolean.TRUE.equals(state.value(K_REQUIRED_OK, false));
            boolean ok = sensitiveOk && requiredOk;
            String note = String.join("；",
                    String.valueOf(state.value(K_SENSITIVE_NOTE, "")),
                    String.valueOf(state.value(K_REQUIRED_NOTE, "")));
            return Map.of(
                    K_COMPLIANCE_OK, ok,
                    K_COMPLIANCE_NOTE, (ok ? "合规校验通过：" : "合规校验未通过，需人工重点审核：") + note,
                    K_NEXT_NODE, NODE_HUMAN_REVIEW,
                    K_REVIEW_TRAIL, List.of("compliance_merge: ok=" + ok)
            );
        };

        // 节点5：人工审核（interruptBefore 之后才执行，读取人工注入的 approved / review_comment）
        NodeAction humanReviewNode = state -> {
            boolean approved = Boolean.TRUE.equals(state.value(K_APPROVED, false));
            String comment = String.valueOf(state.value(K_REVIEW_COMMENT, ""));
            return Map.of(
                    K_FINAL_STATUS, "human_reviewed",
                    K_NEXT_NODE, approved ? NODE_APPROVE : NODE_REJECT,
                    K_REVIEW_TRAIL, List.of("human_review: " + (approved ? "批准" : "驳回")
                            + (comment.isBlank() ? "" : "，意见=" + comment))
            );
        };

        // 节点6：审批通过
        NodeAction approveNode = state -> Map.of(
                K_APPROVED, true,
                K_FINAL_STATUS, "approved",
                K_REVIEW_TRAIL, List.of("approve: 文档已放行")
        );

        // 节点7：审批驳回
        NodeAction rejectNode = state -> Map.of(
                K_APPROVED, false,
                K_FINAL_STATUS, "rejected",
                K_REVIEW_TRAIL, List.of("reject: 文档已驳回")
        );

        // ========== 3. 组装图：添加节点 + 连接边 ==========
        this.stateGraph = new StateGraph(keyStrategyFactory)
                .addNode(NODE_INGEST, node_async(ingestNode))
                .addNode(NODE_CLASSIFY, node_async(classifyNode))
                .addNode(NODE_SENSITIVE_CHECK, node_async(sensitiveNode))
                .addNode(NODE_REQUIRED_CHECK, node_async(requiredNode))
                .addNode(NODE_COMPLIANCE_MERGE, node_async(complianceMergeNode))
                .addNode(NODE_HUMAN_REVIEW, node_async(humanReviewNode))
                .addNode(NODE_APPROVE, node_async(approveNode))
                .addNode(NODE_REJECT, node_async(rejectNode))

                // 顺序主流程
                .addEdge(StateGraph.START, NODE_INGEST)
                .addEdge(NODE_INGEST, NODE_CLASSIFY)

                // 并行扇出：两个检查互不依赖，同时开始
                .addEdge(NODE_CLASSIFY, NODE_SENSITIVE_CHECK)
                .addEdge(NODE_CLASSIFY, NODE_REQUIRED_CHECK)
                // 并行扇入：两个都完成后才进汇合节点
                .addEdge(NODE_SENSITIVE_CHECK, NODE_COMPLIANCE_MERGE)
                .addEdge(NODE_REQUIRED_CHECK, NODE_COMPLIANCE_MERGE)
                .addEdge(NODE_COMPLIANCE_MERGE, NODE_HUMAN_REVIEW)

                // 条件边：人工审核后按 next_node 分流（边只做转发，决策在节点里）
                .addConditionalEdges(
                        NODE_HUMAN_REVIEW,
                        edge_async(state -> state.value(K_NEXT_NODE, NODE_REJECT).toString()),
                        Map.of(
                                NODE_APPROVE, NODE_APPROVE,
                                NODE_REJECT, NODE_REJECT
                        )
                )

                // 终点
                .addEdge(NODE_APPROVE, StateGraph.END)
                .addEdge(NODE_REJECT, StateGraph.END);

        // ========== 4. 导出 Mermaid 流程图 ==========
        System.out.println("====== Mermaid 流程图 ======");
        System.out.println(stateGraph
                .getGraph(GraphRepresentation.Type.MERMAID, "文档审批流程")
                .content());
        System.out.println("============================");

        // ========== 5. 编译 + 配置持久化与中断点 ==========
        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder()
                        .register(new MemorySaver())
                        .build())
                .interruptBefore(NODE_HUMAN_REVIEW) // 在人工审核节点【之前】暂停
                .build();

        this.compiledGraph = stateGraph.compile(compileConfig);
    }

    /**
     * LLM 分类：输出归一化 + 失败兜底。
     * 模型可能返回 "policy。"、"`contract`"、"根据内容判断：other" 等，
     * 不能直接拿原话当枚举用；调用失败时降级为 other，不让整个流程崩掉。
     */
    private String classify(String text) {
        try {
            String raw = chatClient.prompt()
                    .user("将下面文档分类为 policy / contract / other，只返回类别单词，不要多余内容：\n" + text)
                    .call()
                    .content();
            if (raw == null) {
                return "other";
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            return DOC_TYPES.contains(normalized) ? normalized : "other";
        } catch (Exception e) {
            return "other"; // 分类是辅助信息，失败不应阻断审批流程
        }
    }

    /**
     * 提交文档，执行到人工审核前中断
     */
    @Operation(summary = "提交文档审批（跑到 human_review 前暂停）")
    @PostMapping("/api/s61/submit")
    public Map<String, Object> submit(@RequestBody SubmitReq req) {
        // 不传 threadId 就生成一个，避免所有人共用默认 id 导致会话串味
        String threadId = (req == null || req.threadId() == null || req.threadId().isBlank())
                ? "doc-" + UUID.randomUUID()
                : req.threadId();
        String docText = (req == null || req.docText() == null || req.docText().isBlank())
                ? "本公司考勤制度规定员工须按时打卡，迟到需说明原因并报备。"
                : req.docText();

        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId(threadId)
                .build();

        compiledGraph.invoke(Map.of(K_DOC_TEXT, docText), runnableConfig);

        // 中断后回读快照，把当前状态原样返回给前端
        return snapshotView(threadId);
    }

    /**
     * 人工审批后恢复流程
     */
    @Operation(summary = "人工审批恢复流程")
    @PostMapping("/api/s62/resume")
    public Map<String, Object> resume(@RequestBody ResumeReq req) {
        if (req == null || req.threadId() == null || req.threadId().isBlank()) {
            return Map.of("error", "threadId 不能为空，且与 submit 时必须一致");
        }
        if (req.approved() == null) {
            return Map.of("error", "approved 必须显式传 true/false，缺省可能导致误放行");
        }
        String threadId = req.threadId();

        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId(threadId)
                .build();

        // 1. 取中断处快照（须与 submit 使用同一 threadId）
        StateSnapshot snapshot = compiledGraph.getState(runnableConfig);
        if (snapshot == null || snapshot.state() == null) {
            return Map.of("error", "未找到 threadId=" + threadId + " 的快照：请先调用 submit，或检查 threadId 是否一致");
        }
        OverAllState state = snapshot.state();

        // 2. 已终结的会话不允许重复审批
        String status = String.valueOf(state.value(K_FINAL_STATUS, ""));
        if (TERMINAL_STATUS.contains(status)) {
            return Map.of("error", "该会话已审批完成（final_status=" + status + "），不能重复审批",
                    "threadId", threadId);
        }

        // 3. 写入人工结论。禁止 state.data().put(...) —— data() 是不可变视图，会抛 UnsupportedOperationException
        Map<String, Object> humanInput = new HashMap<>();
        humanInput.put(K_APPROVED, req.approved());
        if (req.comment() != null && !req.comment().isBlank()) {
            humanInput.put(K_REVIEW_COMMENT, req.comment());
        }
        state.updateState(humanInput);

        // 4. withResume() 标记续跑，再 invoke 从断点继续（human_review → 条件边分流）
        RunnableConfig resumeConfig = runnableConfig.withResume();
        compiledGraph.invoke(state, resumeConfig);

        return snapshotView(threadId);
    }

    /**
     * 查看当前会话快照：调试、审计、时间旅行都靠它
     */
    @Operation(summary = "查看当前会话状态快照")
    @GetMapping("/api/s63/state")
    public Map<String, Object> state(@RequestParam(defaultValue = "doc-001") String threadId) {
        StateSnapshot snapshot = compiledGraph.getState(
                RunnableConfig.builder().threadId(threadId).build());
        if (snapshot == null || snapshot.state() == null) {
            return Map.of("threadId", threadId, "status", "not_found");
        }
        return snapshotView(threadId);
    }

    /**
     * 获取 Mermaid 流程图文本
     */
    @Operation(summary = "获取 Mermaid 流程图")
    @GetMapping("/api/s62/mermaid")
    public String getMermaid() {
        return stateGraph.getGraph(GraphRepresentation.Type.MERMAID, "文档审批流程").content();
    }

    /** 统一的状态视图：中断态和终态用同一套字段返回，前端不用分两套解析 */
    private Map<String, Object> snapshotView(String threadId) {
        StateSnapshot snapshot = compiledGraph.getState(
                RunnableConfig.builder().threadId(threadId).build());
        if (snapshot == null || snapshot.state() == null) {
            return Map.of("threadId", threadId, "status", "not_found");
        }
        OverAllState s = snapshot.state();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("threadId", threadId);
        view.put("status", String.valueOf(s.value(K_FINAL_STATUS, "pending_review")));
        view.put("docType", s.value(K_DOC_TYPE, ""));
        view.put("sensitiveOk", s.value(K_SENSITIVE_OK, false));
        view.put("requiredOk", s.value(K_REQUIRED_OK, false));
        view.put("complianceOk", s.value(K_COMPLIANCE_OK, false));
        view.put("complianceNote", s.value(K_COMPLIANCE_NOTE, ""));
        view.put("approved", s.value(K_APPROVED, false));
        view.put("reviewComment", s.value(K_REVIEW_COMMENT, ""));
        view.put("nextNode", s.value(K_NEXT_NODE, ""));
        view.put("trail", s.value(K_REVIEW_TRAIL, List.of()));
        return view;
    }

    public record SubmitReq(String threadId, String docText) {
    }

    public record ResumeReq(String threadId, Boolean approved, String comment) {
    }
}