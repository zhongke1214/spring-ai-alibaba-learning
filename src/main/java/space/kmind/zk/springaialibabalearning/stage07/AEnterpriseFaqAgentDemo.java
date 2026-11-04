package space.kmind.zk.springaialibabalearning.stage07;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 装配层：一个 Controller 里同时演示两条链路。
 *
 * /api/s71/ask         Agent 链路：模型自己决定要不要检索（适合「有时闲聊、有时查制度」）
 * /api/s72/ask-advisor Advisor 链路：固定先检索再答（适合纯 FAQ：行为可预测、延迟稳定）
 * /api/s73/ingest      覆盖式入库
 *
 * 两条链路共用同一套检索（HybridRetriever）和同一份权限（UserScope），
 * 区别只在「谁来决定检索时机」：Agent 交给模型，Advisor 交给代码（见 7.1）。
 *
 * [生产] 这个类要拆开：Controller 只做鉴权和参数校验，
 *        检索、Agent 装配、摄入各自独立成 Bean，再补上超时、限流、审计日志和缓存。
 */
@Tag(name = "Stage07-RAG", description = "企业 FAQ：权限过滤 + 引用溯源 + 拒答")
@RestController
public class AEnterpriseFaqAgentDemo {

    /** [教学] 召回条数写死 4。 [生产] 按场景分档（FAQ 4~6，要覆盖多条制度时 8~10），放配置中心 */
    private static final int TOP_K = 4;

    /**
     * 系统提示词。五条规则一条对应正文里的一个坑：
     * 1、2 约束「只依据检索结果回答」；3 只让模型报编号而不是 doc_id，
     * 因为随机字符串它记不住，还会编（见 5.1）；4 是 EMPTY 信号的接收端（见 7.3）；
     * 5 让闲聊不必检索，省一次 embedding。
     *
     * [教学] 写死在代码里，改一次发一次版。
     * [生产] 放配置中心或提示词管理平台，带版本号，改 prompt 能灰度、能回滚；
     *        并且绝不能把「年假 10 天」这类事实写进来——写了就是第二个信息源，
     *        两边不一致时模型会挑那个更顺口的（见 7.3）。
     */
    static final String SYSTEM_PROMPT = """
            你是企业内部制度问答助手。
            1. 只要问题涉及考勤、假期、报销、差旅、福利、入离职，先调用 searchKb，再回答。
            2. 只依据 searchKb 返回的片段回答，不要补充片段之外的任何内容。
            3. 每个结论后面用 [1]、[2] 标注依据的片段编号，编号必须来自本次检索结果。
            4. 检索结果为 EMPTY，或片段中确实没有答案时，回答「知识库暂未收录，建议联系 HR 或行政确认」。禁止猜测、禁止凭常识补全。
            5. 与公司制度无关的问题，直接简短回答，不必检索。
            """;

    /**
     * 演示用用户目录：userId -> 可见范围。
     *
     * [教学] 三个写死的用户，接口里直接传 userId，方便 curl 切换身份。
     * [生产] 换成 SSO / 员工系统 / SecurityContext：
     *        - 身份只能来自服务端已验过的地方，请求体里的 userId 一律不认；
     *        - 查不到这个人就按「无权限」处理，绝不能 fallback 成「全部可见」；
     *        - 结果要缓存（带 TTL），别每次问答都打一次员工系统。
     */
    private static final Map<String, UserScope> USERS = Map.of(
            "u1001", new UserScope("u1001", "hr", Set.of("employee")),
            "u2002", new UserScope("u2002", "finance", Set.of("employee")),
            "u9000", new UserScope("u9000", "ops", Set.of("kb_admin"))
    );

    private final FaqCorpus corpus;
    private final HybridRetriever retriever;
    private final ChatClient chatClient;
    private final ReactAgent agent;

    /**
     * 本次会话的检索证据：threadId -> 这一轮实际喂给模型的片段。
     *
     * [教学] 用内存 Map 中转，因为工具方法拿不到 Controller 的局部变量。
     * [生产] 三个必须改的点：
     *        1. 多实例部署时内存 Map 会串（A 命中、B 拿不到），要落 Redis；
     *        2. 并发下同一个 threadId 的两轮会互相覆盖，要带轮次或 requestId；
     *        3. 审计要求「这个答案当时看到了什么」，所以要落库（requestId + hits）而不是用完即弃。
     */
    private final Map<String, List<Document>> evidence = new ConcurrentHashMap<>();

    public AEnterpriseFaqAgentDemo(ChatModel chatModel,
                                   ChatClient.Builder clientBuilder,
                                   FaqCorpus corpus,
                                   HybridRetriever retriever) {
        this.corpus = corpus;
        this.retriever = retriever;
        this.chatClient = clientBuilder.build();   // 基础对话客户端，RAG 由每个请求的 Advisor 负责

        FaqTools tools = new FaqTools();
        this.agent = ReactAgent.builder()
                .name("enterprise_faq")
                .model(chatModel)
                .description("企业内部制度问答")
                .systemPrompt(SYSTEM_PROMPT)
                // 注意：ReactAgent 的 .tools(...) 只吃 ToolCallback，
                // 带 @Tool 注解的 POJO 要用 .methodTools(...)（ChatClient 那边才是 .tools(...) 直接吃 POJO）
                .methodTools(tools)
                // [教学] MemorySaver 把 checkpoint 放内存，重启即失忆。
                // [生产] 换成 Redis / 数据库的 CheckpointSaver，否则服务一重启多轮对话全断（见第 03 篇）
                .saver(new MemorySaver())
                .build();

        //********************防止每次启动项目该地方消耗向量模型额度，这里先注释掉，要测试案例时请手动恢复。********************
//        seed();   // [生产] 删掉：启动即灌库只适合教学，真实语料走 /api/s73/ingest 或独立流水线
    }

    /** 启动即灌四份样例制度。 [生产] 删除整个方法 */
    private void seed() {
        corpus.ingest(new FaqDoc("all-handbook", "员工手册（通用条款）", "all", "2025", "2025-01-01", """
                第 1 条 适用范围
                本手册适用于公司全体正式员工与实习生，外包人员参照执行。
                第 2 条 工作时间
                标准工时制，每周五天、每天八小时，弹性打卡区间 9:00-10:30。
                第 3 条 假期
                年假、病假、婚假等具体天数见各部门《假期管理办法》，本手册不重复规定。
                第 4 条 保密
                薪资、客户名单、未公开的经营数据属于保密信息，禁止外发。
                """));

        corpus.ingest(new FaqDoc("hr-leave", "员工考勤与假期管理办法", "hr", "2025", "2025-01-01", """
                第 1 条 年假
                司龄满 1 年不满 3 年，年假 10 天；满 3 年不满 5 年，12 天；满 5 年起 15 天。
                年假以自然年为周期计算，当年未休完的部分可延至次年 3 月 31 日，逾期作废。
                第 2 条 病假
                病假需提供二级以上医院证明，当月累计超过 3 天的，需部门负责人审批。
                病假工资按当地最低工资标准的 80% 发放，医疗期内不得解除劳动合同。
                第 3 条 调休
                法定节假日加班的，按 1:1 安排调休；休息日加班未安排调休的，按 2 倍工资支付。
                第 4 条 请假流程
                所有请假须提前在 OA 提交，3 天以内由直属主管审批，超过 3 天需上一级负责人审批。
                """));

        corpus.ingest(new FaqDoc("fin-016", "费用报销凭证规范", "finance", "2025", "2025-01-01", """
                制度编号：A-2025-016
                第 1 条 适用范围
                本规范适用于差旅费、业务招待费、办公采购等全部费用报销场景。
                第 2 条 发票要求
                报销须提供发票原件照片，发票抬头必须与公司全称一致，税号不得缺漏。
                电子发票需同时提交 PDF 原件，截图不予受理。
                第 3 条 报销单填写
                报销单须填写事由、金额、发生日期，并关联对应的审批单号。
                出租车、网约车费用须注明起止地点与事由，否则财务有权退回。
                """));

        corpus.ingest(new FaqDoc("fin-017", "差旅住宿与交通标准", "finance", "2025", "2025-01-01", """
                制度编号：A-2025-017
                第 1 条 住宿标准
                一线城市（北京、上海、广州、深圳）每晚不超过 500 元；二线城市不超过 350 元；其他城市不超过 280 元。
                超出标准部分由个人承担，特殊情况需事前审批。
                第 2 条 交通工具
                高铁二等座、飞机经济舱据实报销；市内交通优先选择公共交通。
                第 3 条 出差审批
                出差前须在 OA 提交出差申请，注明目的地、起止日期与预计费用。
                """));
    }

    // ---------------------------------------------------------------- Agent 链路

    /**
     * Agent 链路：检索时机由模型决定。适合「一半闲聊、一半查制度」的场景。
     */
    @Operation(summary = "FAQ 提问（Agent 自己决定要不要检索）")
    @PostMapping("/api/s71/ask")
    public FaqAnswer ask(@RequestBody AskReq req) throws GraphRunnerException {
        // [教学] threadId 由前端传，省得演示时还要先建会话。
        // [生产] 服务端生成并绑定当前登录用户，否则知道别人的 threadId 就能接着别人的会话问
        String threadId = Optional.ofNullable(req.threadId()).orElse("faq-1");

        // [教学] userId 从请求体取，只为 curl 切身份方便。
        // [生产] 从 SecurityContext / 网关下发的可信用户头取，请求体里的一律不认（见 3.3）
        String userId = Optional.ofNullable(req.userId()).orElse("u1001");

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .addMetadata("user_id", userId)     // 身份走 metadata：模型看不到，也改不了（见 7.2）
                .addMetadata("thread_id", threadId)
                .build();

        evidence.remove(threadId);                 // 清掉上一轮，避免把旧证据当成本轮来源
        Optional<OverAllState> out = agent.invoke(req.question(), config);

        String answer = out.map(AEnterpriseFaqAgentDemo::lastAnswer).orElse("");
        List<Document> hits = evidence.getOrDefault(threadId, List.of());
        // grounded 由后端算：来源为空却答得很具体，说明它不是「看着资料答」的（见 5.4）
        return new FaqAnswer(answer, toSources(hits), !hits.isEmpty());
    }

    /**
     * 取最后一条「没有工具调用」的 assistant 消息，那就是最终答复
     * ——中间那些带 toolCalls 的消息是「我要查一下」，不是答案。
     *
     * [教学] 直接翻 messages 列表，最直观。
     * [生产] 用 Agent 的结构化输出（见第 04 篇）把答案写进指定字段，
     *        比在消息列表里倒着找稳，也不会因为多轮工具调用而取错。
     */
    @SuppressWarnings("unchecked")
    private static String lastAnswer(OverAllState state) {
        List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage am && !am.hasToolCalls()) {
                return am.getText();
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- Advisor 链路

    /**
     * Advisor 链路：固定「先检索 → 再生成」，模型没有选择权。
     * 纯 FAQ 场景推荐这条：行为确定、延迟稳定、测试好写（见 7.1）。
     */
    @Operation(summary = "FAQ 提问（固定先检索再答）")
    @PostMapping("/api/s72/ask-advisor")
    public FaqAnswer askByAdvisor(@RequestBody AskReq req) {
        String userId = Optional.ofNullable(req.userId()).orElse("u1001");
        UserScope scope = USERS.getOrDefault(userId, USERS.get("u1001"));

        RetrievalAugmentationAdvisor advisor = RetrievalAugmentationAdvisor.builder()
                // 用 lambda 把 scope 包进检索器，这个请求的权限就被固定住了；
                // 也正因为如此，这里需要每个请求 new 一个 Advisor。
                // [生产] 如果用官方的 VectorStoreDocumentRetriever，Advisor 可以做成单例，
                //        权限通过 VectorStoreDocumentRetriever.FILTER_EXPRESSION 这个上下文参数按请求传入（见 3.2）
                .documentRetriever(query -> retriever.search(query.text(), scope, TOP_K))
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(false)                              // 空上下文 → 把问题整段换成拒答指令（见 6.2）
                        .documentFormatter(AEnterpriseFaqAgentDemo::numbered)  // 片段带 [1][2] 编号，模型只报号
                        .build())
                .build();

        ChatResponse response = chatClient.prompt()
                .user(req.question())
                .advisors(advisor)
                .call()
                .chatResponse();

        // 官方文档：这次实际用了哪些文档，写在 DOCUMENT_CONTEXT（值 "rag_document_context"）里。
        // 以这份列表为准来组装 sources，模型正文里标了什么只当增强（见 5.2）
        @SuppressWarnings("unchecked")
        List<Document> used = response.getMetadata()
                .getOrDefault(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());

        return new FaqAnswer(response.getResult().getOutput().getText(), toSources(used), !used.isEmpty());
    }

    // ---------------------------------------------------------------- 摄入

    /**
     * 覆盖式入库：同 docId 再灌一次等于换版本。
     *
     * [教学] 谁都能调，同步执行，返回切了几片。
     * [生产] 三件事：加鉴权（能灌库等于能改公司制度，权限要给到知识库管理员）；
     *        返回任务 id 异步跑（几百页文档同步跑会超时）；
     *        成功后 kbVersion +1，让缓存失效（见 8.4）。
     */
    @Operation(summary = "覆盖式入库：同 docId 再灌一次等于换版本")
    @PostMapping("/api/s73/ingest")
    public Map<String, Object> ingest(@RequestBody FaqDoc doc) {
        int chunks = corpus.ingest(doc);
        return Map.of("docId", doc.docId(), "version", doc.version(), "chunks", chunks);
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 挂在 Agent 上的工具。注意它只有一个 query 参数——
     * 不给模型任何「指定部门 / 指定身份」的入口，是权限设计的第一道保险（见 7.2）。
     */
    class FaqTools {

        @Tool(description = "在企业制度知识库中检索片段。涉及考勤、假期、报销、差旅、福利、入离职时必须先调用；闲聊不用调。只传问题原文，不要传部门或身份信息。")
        public String searchKb(@ToolParam(description = "用户问题原文") String query, ToolContext ctx) {
            // 身份来自 RunnableConfig.metadata，经 ToolContext 传进来——模型既看不到也改不了
            String userId = String.valueOf(ctx.getContext().getOrDefault("user_id", ""));
            String threadId = String.valueOf(ctx.getContext().getOrDefault("thread_id", "default"));

            UserScope scope = USERS.get(userId);
            if (scope == null) {
                return "EMPTY";                 // 认不出人，就当没权限（默认拒绝，不是默认放行）
            }

            List<Document> hits = retriever.search(query, scope, TOP_K);
            evidence.put(threadId, hits);       // 留痕，接口层据此返回 sources

            if (hits.isEmpty()) {
                return "EMPTY";                 // 固定信号：比空列表更容易约束模型（见 7.3）
            }
            return numbered(hits);
            // [生产] 三件事：给工具加调用次数上限和超时（防止模型反复检索）；
            //        把 hits 直接写进 OverAllState 或落库，别依赖内存 Map；
            //        记录一次检索日志（query + scope + 命中的 docId），这是后面做评测集的原料
        }
    }

    // ---------------------------------------------------------------- 工具方法

    /**
     * 拼给模型看的片段：带编号、带标题、带段号。
     * 模型只需要引用编号，不需要描述来源——输出更稳（见 5.3）。
     *
     * [生产] 片段长度要设上限（比如每段 800 字），别让 topK 变大时把上下文撑爆；
     *        另外可以在编号行里带上 doc_id，方便出问题后按引用反查
     */
    static String numbered(List<Document> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Document d = hits.get(i);
            sb.append('[').append(i + 1).append("] 《")
                    .append(d.getMetadata().get("title")).append('》');
            Object idx = d.getMetadata().get("chunk_index");
            if (idx instanceof Number n) {
                sb.append(" 第 ").append(n.intValue() + 1).append(" 段");
            }
            sb.append('\n').append(d.getText()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 返回给前端的来源：编号和正文里的 [1][2] 一一对应。
     *
     * [教学] snippet 截断到 120 字。
     * [生产] Source 里补上 version 和 source_uri：
     *        chunkIndex 只能定位「当前版本的第 N 段」，制度一改版就对不上了，
     *        要做原文跳转必须带版本号和原文链接（见 10.4 ⑤）。
     */
    static List<Source> toSources(List<Document> hits) {
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            Document d = hits.get(i);
            String text = d.getText();
            Object idx = d.getMetadata().get("chunk_index");
            sources.add(new Source(
                    i + 1,
                    String.valueOf(d.getMetadata().get("doc_id")),
                    String.valueOf(d.getMetadata().get("title")),
                    idx instanceof Number n ? n.intValue() : null,
                    text.length() > 120 ? text.substring(0, 120) + "…" : text,
                    d.getScore()));
        }
        return sources;
    }

    // ---------------------------------------------------------------- DTO

    /**
     * [教学] 带 userId 是为了 curl 能切身份。
     * [生产] 去掉 userId，身份从登录态取；再加一个可选的 dept 收窄条件时，
     *        必须和登录态对得上才认，对不上就退回真实部门（见 3.2）
     */
    public record AskReq(String question, String threadId, String userId) {}

    /**
     * 一条引用。no 对应正文里的 [1]，docId 用于跳转，snippet 给人判断「是不是这条」。
     * [生产] 补 version / sourceUri / tenant，审计时才知道「他当时看到的是哪一版」
     */
    public record Source(int no, String docId, String title,
                         Integer chunkIndex, String snippet, Double score) {}

    /**
     * 回答。grounded = 这次回答有没有依据。
     * [生产] 补 requestId（串日志）、kbVersion（答案对应哪版知识库）、confidence（降级检索时标 low）、latency（观测）
     */
    public record FaqAnswer(String answer, List<Source> sources, boolean grounded) {}
}