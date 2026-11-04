package space.kmind.zk.springaialibabalearning.stage07;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合召回：向量管「像」，关键词管「准」，RRF 融合。
 *
 * 为什么用 RRF 而不是加权求和：向量分和关键词分不是一个量纲，
 * 权重怎么调都是玄学，换个 embedding 模型就得重调；RRF 只看名次，天然免疫这个问题（见 4.2）。
 *
 * [生产] 这里没做、但生产该做的三件事：
 *        1. 两路召回并行（CompletableFuture），现在是串行，延迟等于两路之和；
 *        2. 融合之后加一层 rerank（Cross-Encoder 或百炼 gte-rerank），
 *           用 DocumentPostProcessor 接进 RetrievalAugmentationAdvisor；
 *        3. 本轮命中落检索证据表（requestId + docId + chunkIndex + score），供审计和评测回放。
 */
@Component
public class HybridRetriever {

    private static final int RRF_K = 60;            // 论文经验值，60 是个不容易出错的起点

    /**
     * 相似度下限。
     *
     * [教学] 0.35 是照这四份样例文档拍的，换语料必须重标。
     * [生产] 按 6.4 的土办法标定：20 条域内 + 20 条域外，取两组分数分布之间的「沟」。
     *        另外记住它是客户端后置过滤——向量库照样算满 topK，所以想省成本调 topK，不是调阈值。
     */
    private static final double MIN_SCORE = 0.35;

    private final VectorStore vectorStore;
    private final FaqCorpus corpus;

    public HybridRetriever(VectorStore vectorStore, FaqCorpus corpus) {
        this.vectorStore = vectorStore;
        this.corpus = corpus;
    }

    /**
     * 一次混合检索。返回的是「已经过权限、已按 RRF 排好序」的片段列表，
     * 上游只管拿去拼 Prompt，不用再判断权限。
     */
    public List<Document> search(String query, UserScope scope, int topK) {
        // 1) 向量召回：多取一倍留给融合
        //    [生产] 这个放大系数（2）是可调参数，按召回质量调，别写死在代码里
        List<Document> byVector = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK * 2)
                .similarityThreshold(MIN_SCORE)
                .filterExpression(scope.filter())    // 权限在这里生效：无权的内容压根不进候选
                .build());

        // 2) 关键词召回：同样要过权限（传的是同一个 scope 对象，规则不可能不一致）
        List<Document> byKeyword = corpus.keywordSearch(query, scope, topK * 2);

        // 3) RRF 融合
        return fuse(byVector, byKeyword, topK);
    }

    /**
     * RRF：score(d) = Σ 1 / (k + rank_i(d))，k = 60。
     *
     * [教学] 同步串行、单线程，逻辑看得最清楚。
     * [生产] 这里是接 rerank 的位置：融合完先别急着截断，
     *        取 topK*2 交给 Cross-Encoder 重排后再截到 topK，长尾问题的改善很明显。
     */
    private static List<Document> fuse(List<Document> a, List<Document> b, int topK) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> byId = new HashMap<>();
        for (List<Document> list : List.of(a, b)) {
            for (int i = 0; i < list.size(); i++) {
                Document d = list.get(i);
                scores.merge(d.getId(), 1.0 / (RRF_K + i + 1), Double::sum);  // 名次越靠前分越高
                byId.putIfAbsent(d.getId(), d);   // 同一文档两路都命中时，保留先出现的那个
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byId.get(e.getKey()))
                .toList();
    }
}