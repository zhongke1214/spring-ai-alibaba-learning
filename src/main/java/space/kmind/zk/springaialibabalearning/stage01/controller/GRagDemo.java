package space.kmind.zk.springaialibabalearning.stage01.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 演示控制器（基于 SimpleVectorStore，无需外部向量数据库）。
 *
 * SimpleVectorStore 是 Spring AI 内置的内存向量存储：
 * - 数据存在 JVM 内存中，应用重启即丢失，仅用于开发测试和教学演示；
 * - 依赖 EmbeddingModel 把文本转成向量，EmbeddingModel 由 Spring AI 自动配置注入。
 *
 * 本控制器演示两种场景：
 * 1. 正常 RAG：QuestionAnswerAdvisor 自动完成"检索 + 拼 Prompt + 调用模型"。
 * 2. 降级处理：检索为空时返回兜底提示，而不是让模型自由发挥（避免幻觉）。
 */
@Tag(name = "Stage01-1.7 RAG 演示", description = "基于 SimpleVectorStore 的 RAG 演示")
@RestController
public class GRagDemo {

    private static final Logger log = LoggerFactory.getLogger(GRagDemo.class);

    /** 相似度阈值：低于此值的片段被丢弃（内存演示用 0.5，生产需按模型实测调整） */
    private static final double SIMILARITY_THRESHOLD = 0.5d;

    /** TopK：最多返回几条最相关片段 */
    private static final int TOP_K = 3;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    /**
     * 构造注入：
     * - ChatClient.Builder：Spring AI 自动配置提供；
     * - EmbeddingModel：用于把文本转成向量，SimpleVectorStore 依赖它工作。
     */
    public GRagDemo(ChatClient.Builder builder, EmbeddingModel embeddingModel) {

        // ---------- 1. 创建内存向量存储 ----------
        this.vectorStore = SimpleVectorStore.builder(embeddingModel).build();

        // ---------- 2. 创建 RAG Advisor ----------
        // QuestionAnswerAdvisor 会自动：检索向量库 → 把相关片段拼进 Prompt → 调用模型
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .topK(TOP_K)
                        .build())
                .build();

        // ---------- 3. 构建 ChatClient ----------
        this.chatClient = builder
                .defaultAdvisors(qaAdvisor)
                .build();
    }

    /**
     * 预置知识库文档。
     * 使用 @PostConstruct 保证在构造完成后、请求到达前执行。
     * 实际项目中这些文档来自 PDF、数据库或爬虫，这里手动写入几条用于演示。
     */
    @PostConstruct
    public void initKnowledgeBase() {
        List<Document> documents = List.of(
                new Document("公司年假为 10 天，入职满 3 年后增至 15 天，满 5 年增至 20 天。"),
                new Document("请假需提前 3 个工作日通过 OA 系统提交申请，由直属主管审批。"),
                new Document("公司报销流程：在 OA 系统提交报销申请，附上发票原件照片，直属主管审批后由财务部复核，3 个工作日内打款。"),
                new Document("差旅报销标准：一线城市住宿每晚不超过 500 元，二线城市不超过 350 元。"),
                new Document("年终奖于每年 1 月 15 日发放，计算基数为上一年度 12 个月的平均月薪。"),
                new Document("五险一金按照国家规定缴纳，公积金缴存比例为 12%。")
        );

        vectorStore.add(documents);
        log.info("【RAG 初始化】已向 SimpleVectorStore 写入 {} 条知识文档", documents.size());
    }

    /**
     * 正常 RAG 问答。
     * QuestionAnswerAdvisor 自动完成检索和 Prompt 增强，代码上和一个普通对话没区别。
     *
     * 访问示例：
     *   /api/s16/rag/chat?q=公司年假多少天     ← 知识库有，基于资料回答
     *   /api/s16/rag/chat?q=食堂今天吃什么     ← 知识库没有，观察模型如何回答
     */
    @GetMapping("/api/s16/rag/chat")
    public String chat(@RequestParam String q) {
        log.info("【RAG 请求】用户提问：{}", q);
        return chatClient.prompt()
                .user(q)
                .call()
                .content();
    }

    /**
     * 降级演示：先手动检索，检索为空则直接返回兜底提示，不调用模型。
     *
     * 为什么需要降级？
     * 当知识库里没有相关内容时，如果直接交给模型，模型可能凭训练记忆编造答案（幻觉）。
     * 正确做法是：检索为空 → 明确告知用户"没有找到相关资料"。
     *
     * 访问示例：
     *   /api/s16/rag/chat-fallback?q=公司年假多少天   ← 有资料，正常回答
     *   /api/s16/rag/chat-fallback?q=食堂今天吃什么   ← 无资料，触发降级
     */
    @GetMapping("/api/s16/rag/chat-fallback")
    public String chatWithFallback(@RequestParam String q) {
        log.info("【RAG 降级演示】用户提问：{}", q);

        // 1. 手动执行相似度检索
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(q)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .topK(TOP_K)
                        .build()
        );

        // 2. 检索为空 → 降级：直接返回兜底提示，不调用模型
        if (docs == null || docs.isEmpty()) {
            log.warn("【RAG 降级】检索为空，触发兜底响应。query={}", q);
            return "抱歉，知识库中没有找到与您问题相关的资料。"
                    + "请尝试换个问法，或联系人工客服获取帮助。";
        }

        // 3. 检索到内容 → 打印命中片段，便于观察
        log.info("【RAG 检索】命中 {} 条片段：", docs.size());
        for (Document doc : docs) {
            log.info("  - {}", doc.getText());
        }

        // 4. 把检索到的片段拼进 Prompt，交给模型生成
        StringBuilder context = new StringBuilder();
        for (Document doc : docs) {
            context.append(doc.getText()).append("\n");
        }

        String prompt = """
                请根据以下资料回答问题。如果资料中没有相关信息，请明确说明"资料中未提及"。

                资料：
                %s

                问题：%s
                """.formatted(context, q);

        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}