package space.kmind.zk.springaialibabalearning.stage07;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识资产层：切分 → 写向量库 → 写关键词索引 → 记台账。
 *
 * 注释里的两种标记（见 10.1 开头的说明）：
 * [教学] = 为了让这个 Demo 能独立跑起来而做的简化，直接抄到生产会出事；
 * [生产] = 上生产应该换成什么，以及为什么必须换。
 *
 * 这一段一次性覆盖三个容易踩的点：
 * 1. 切分：中文按「条」给换行，TokenTextSplitter 只认 . ? ! \n 四个断句字符，不认中文句号；
 * 2. 版本替换：chunk id 每次重灌都会变，必须自己维护 docId -> chunkIds 台账，才能删旧写新；
 * 3. 权限：向量和关键词两路召回共用同一份规则（都来自 UserScope），少一条就是越权后门。
 */
@Component
public class FaqCorpus {

    /**
     * 切分器。这里用 TokenTextSplitter 是因为它随 Spring AI 自带、不用加依赖；
     * 中文制度文档更建议换 SentenceSplitter 或 RecursiveCharacterTextSplitter（见 2.1）。
     *
     * [教学] 300 token 是照这四份只有几百字的样例文档定的，真实语料请按 2.2 的表重新标定。
     * [生产] 参数放配置中心（改了不用发版），切分器本身做成一个可替换的 Bean：
     *        换切分器 = 全量重灌，出问题时得有回滚开关。
     */
    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(300)          // 一个 chunk 最多 300 token（中文约 400~500 字）
            .withMinChunkSizeChars(120)  // 断点尽量落在 120 字之后，避免切出一两句的碎片
            .withMinChunkLengthToEmbed(10) //切出来的 chunk 如果长度不超过 10（字符），就不放进最终结果，不进入向量化流程。
            .withKeepSeparator(true)     // 保留换行和句号：chunk 可读，前端展示也不突兀
            .build();

    private final VectorStore vectorStore;

    /** [教学] 关键词索引放内存，重启即丢。 [生产] 换 Elasticsearch / OpenSearch 的 BM25，见 10.5 */
    private final KeywordIndex keywordIndex = new KeywordIndex();

    /**
     * doc_id -> 当前版本的 chunk id 列表。
     *
     * [教学] 内存 ConcurrentHashMap：服务一重启台账就没了，旧 chunk 从此变成删不掉的孤儿向量。
     * [生产] 落 Redis 或数据库，与摄入流水线同一个事务，中断后按台账重放。
     *        注意 SimpleVectorStore 只支持按 id 删，所以这张台账是必需品；
     *        换成支持 delete(Filter.Expression) 的向量库后，它退化成一份审计记录（见 8.2）。
     */
    private final Map<String, List<String>> chunkIdsByDoc = new ConcurrentHashMap<>();

    public FaqCorpus(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 覆盖式入库：同一个 docId 再灌一次，就等于换版本（先删旧切片，再写新切片）。
     *
     * [教学] 读文档、切分、写库全塞在一个方法里同步跑，方便单步调试，也方便 curl 复现。
     * [生产] 拆成流水线：接入 → 异步切分与向量化 → 写入 → kbVersion +1，四条硬要求：
     *        1. 向量化是耗时大头，几十页 PDF 同步跑会把 HTTP 线程占死，必须异步 + 队列；
     *        2. 幂等：同样的 docId + version 重复消费，结果必须一致（靠台账先删后写）；
     *        3. 成功后把全局 kbVersion +1，让缓存一刀切失效（见 8.4）；
     *        4. 别停在「旧版已删、新版没写」的中间态——更稳的顺序是先写新版再删旧版，
     *           或者保留多版本、用 version 字段做灰度切换（见 8.3）。
     */
    public int ingest(FaqDoc doc) {
        // 1) 删旧：按 id 删，不能写 delete("doc_id == '...'")——SimpleVectorStore 不支持按表达式删（见 8.1）
        List<String> old = chunkIdsByDoc.getOrDefault(doc.docId(), List.of());
        if (!old.isEmpty()) {
            vectorStore.delete(old);   // [生产] 换成生产级向量库后可直接 delete(Filter.Expression)，但要先确认它真实现了
            keywordIndex.remove(old);  // 两套存储必须同步删，漏一边就是「向量删了但关键词还能搜到旧内容」
        }

        // 2) 父文档：metadata 是权限、版本、引用的全部抓手，一个都不能少
        //    [生产] 还要补 acl_groups（按组授权）、status（draft/active/archived）、
        //           section_path（标题路径）、source_uri（原文跳转）、content_hash（判断内容是否真变了）。
        //           这一步少写一个字段，上线后只能全量重灌补回来（见 2.3.3）。
        List<Document> parent = List.of(Document.builder()
                .id(doc.docId() + "@" + doc.version())
                .text(doc.text())
                .metadata(Map.<String, Object>of(
                        "tenant", "acme",    // [教学] 写死一个租户；[生产] 从租户上下文取，写死就是跨租户泄露
                        "doc_id", doc.docId(),
                        "title", doc.title(),
                        "dept", doc.dept(),
                        "version", doc.version(),
                        "effective_from", doc.effectiveFrom()))
                .build());

        // 3) 切分：metadata 原样继承到每个 chunk，并自动补 parent_document_id / chunk_index / total_chunks
        List<Document> chunks = splitter.split(parent);

        // 4) 写两套存储
        //    [生产] 向量库写成功、关键词索引失败，会变成「向量能搜到、关键词搜不到」的半边状态：
        //          要么让一个引擎同时干两件事（ES 做 BM25 + 向量），要么加补偿任务按台账对账
        vectorStore.add(chunks);
        keywordIndex.add(chunks);

        // 5) 记账：这一轮生成了哪些 chunk id，下次换版本就按这个列表删
        chunkIdsByDoc.put(doc.docId(), chunks.stream().map(Document::getId).toList());
        return chunks.size();
    }

    /**
     * 关键词召回入口。它和向量召回用的是同一个 UserScope 对象——
     * 两路规则不一致，就是一条越权后门（见 4.3）。
     */
    public List<Document> keywordSearch(String query, UserScope scope, int limit) {
        return keywordIndex.search(query, scope, limit);
    }

    /**
     * 教学用关键词索引：内存倒排的极简版，判定逻辑是「包含即命中」。
     *
     * [教学] 每次全量遍历再打分；几百条文档够用，上万条就别想了。
     * [生产] 换成 Elasticsearch / OpenSearch 的 BM25，或者向量库自带的全文检索；
     *        好消息是融合逻辑一行都不用改——RRF 只吃名次，底下换引擎对上层没影响（见 4.2）。
     */
    static class KeywordIndex {

        private final Map<String, Document> docs = new ConcurrentHashMap<>();

        void add(List<Document> chunks) {
            chunks.forEach(d -> docs.put(d.getId(), d));
        }

        void remove(List<String> ids) {
            ids.forEach(docs::remove);
        }

        List<Document> search(String query, UserScope scope, int limit) {
            Set<String> terms = terms(query);
            if (terms.isEmpty()) {
                return List.of();
            }
            return docs.values().stream()
                    .filter(scope::allows)   // 关键：关键词这一路也要过权限，否则就是后门
                    .map(d -> Map.entry(d, score(d.getText(), terms)))
                    .filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.<Document, Double>comparingByValue().reversed())
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .toList();
        }

        /**
         * 打分：命中一次 +1，长度 ≥ 5 的词 +3。
         *
         * [教学] 权重是拍的，目的只是让 A-2025-016 这种编号整段命中时排得更前（见 4.4）。
         * [生产] 用 BM25：它自带 IDF，罕见词权重天然更高，比手工拍系数靠谱得多。
         */
        private static double score(String text, Set<String> terms) {
            String lower = text.toLowerCase(Locale.ROOT);
            double s = 0;
            for (String t : terms) {
                if (lower.contains(t)) {
                    s += t.length() >= 5 ? 3.0 : 1.0;   // 编号这类长 token 权重更高
                }
            }
            return s;
        }

        /**
         * 中文切 2-gram；ASCII 串（A-2025-017）整段当一个词，拆开全是噪声。
         *
         * [教学] 2-gram 是最土但够用的中文分词，代价是「上海」和「海上」会被当成同一个词。
         * [生产] 换成 IK / jieba 这类中文分词器，或者直接交给 ES 的 analyzer。
         */
        private static Set<String> terms(String query) {
            Set<String> terms = new LinkedHashSet<>();
            Matcher m = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9\\-]{3,}").matcher(query);
            while (m.find()) {
                terms.add(m.group().toLowerCase(Locale.ROOT));
            }
            for (String seg : query.replaceAll("[^\\p{IsHan}]", " ").split("\\s+")) {
                for (int i = 0; i + 2 <= seg.length(); i++) {
                    terms.add(seg.substring(i, i + 2));
                }
            }
            return terms;
        }
    }
}

/**
 * 一份待入库的制度文档。
 *
 * [教学] 正文直接写在请求体里，方便 curl 一把复现（见 10.4）。
 * [生产] 换成「文档系统推来的 docId + version + sourceUri」，正文由流水线去对象存储 / OA 拉取；
 *        再补 content_hash（内容没变就别重灌）和 operator（谁改的，审计要用）。
 */
record FaqDoc(String docId, String title, String dept,
              String version, String effectiveFrom, String text) {}