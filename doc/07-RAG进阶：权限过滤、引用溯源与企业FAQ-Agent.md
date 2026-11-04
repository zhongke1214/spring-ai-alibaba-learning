# RAG 进阶：权限过滤、引用溯源与企业 FAQ Agent

> 本讲是《Spring AI Alibaba 学习专栏》的 RAG 进阶篇。底座篇已经把 Embedding → VectorStore → `QuestionAnswerAdvisor` 这条线跑通了，但它只能证明「能搜到」。本篇往企业里再走一步：**切得对、滤得准、说得出来源、搜不到敢说不知道，并且和 Agent 正确结合**。
>
> 版本基准（跟仓库约定对齐）：JDK 17+ / Spring Boot 3.5.7 / Spring AI 1.1.2 / Spring AI Alibaba 1.1.2.2。
>
> 官方文档：<https://java2ai.com>  
> 模块化 RAG / Playground：<https://java2ai.com/docs/1.0.0.2/practices/usecase/playground/>

## 一、Demo 上线就翻车

两周做完公司内部制度问答 Demo：文档全灌进一个库，模型回答得像模像样，评审会上一片掌声。上线第三天，财务的一位同事在群里贴了张截图——他问「年假多少天」，机器人把 HR 的《假期管理办法》第 1 条一字不差地背了出来，包括司龄满 5 年起 15 天这种只对 HR 岗位条款生效的细则。

问题不在模型，在我们：**检索层根本不知道谁能看什么**。向量相似度只会告诉你这段和问题很像，它不负责判断你有没有权限看。

紧接着的第二周，又冒出三个问题：

- 有人问「公司股票代码是多少」，机器人编了一个特别像真的答案；
- 有人拿回答去找 HR 对质，HR 说这条制度去年就改了；
- 审计要核对「这个结论出自哪份文件」，我们只能把整段 Prompt 打出来给人看。

这几件事叠在一起，就是企业 RAG 的五道坎。跟 Demo 阶段的对比很直观：

| 维度  | Demo RAG           | 企业 FAQ          |
| --- | ------------------ | --------------- |
| 语料  | 全文塞进一个库            | 按部门 / 密级 / 租户隔离 |
| 切分  | 整篇灌进去，`topK=5` 随便截 | 切法决定能不能命中       |
| 回答  | 模型自由发挥             | 每条结论能回指片段       |
| 空结果 | 搜不到也硬编             | 明确说不知道          |
| 更新  | 灌一次就不管             | 制度改版要能更新        |

这五件事，没有一件是改改 Prompt 能解决的：语料隔离要动入库流程和检索过滤，切分要动文档预处理，引用要动返回结构和存储格式，拒答要动检索策略和产品交互，更新要建摄入流水线。顺序上，先修入库（切分、元数据），再修检索（过滤、混合召回），再修输出（引用、拒答），最后才是 Agent 集成——Agent 只是把这些能力编排起来，地基不稳，编排再花哨也白搭。

一句话概括目标：**让模型只基于它有权查看的最新资料答题，不编、可溯源、答不了就说不知道。**

---

## 二、切分：检索的上限，在入库那一刻就定了

很多人调 RAG 的顺序是反的：效果一差，先去换模型、调 `topK`、改 Prompt。可如果 chunk 本身切歪了条款被拦腰截断，再强的模型也补不出完整语义；三个条款被硬塞进一个 chunk，向量被平均成一个哪条都不像的混合体，检索时自然谁都不匹配。

**先理解切分在企业级 RAG 中的位置**

Spring AI 将文档处理抽象为 ETL 管道：`DocumentReader` → `DocumentTransformer`（切分器即其中之一）→ `DocumentWriter`。切分器位于**解析之后、向量化之前**，它产出的每一个 `Document` 块将独立进行 Embedding 并进入向量库，后续检索和生成的质量都建立在这些块的质量之上。

### 2.1 Spring AI Alibaba 的切分器家族与选型

#### `2.1.1TokenTextSplitter`：Token 对齐的兜底手段

这是 Spring AI 原生的基础切分器，直接控制每个块的 Token 数量，优势在于**与 LLM 上下文窗口严格对齐**。Spring AI Alibaba 兼容这一接口，并支持配置 `chunkSize` 与 `chunkOverlap`。

```
// Spring AI 原生 TokenTextSplitter 典型配置
@Bean
public TokenTextSplitter tokenTextSplitter() {
    // chunkSize=512, minChunkSize=100, overlap=50, keepSeparator=true
    return new TokenTextSplitter(512, 100, 50, true);
}
```

**四个参数的功能与作用：**

- **chunkSize**：功能是控制每个块允许包含的最大 Token 数；作用是给块长度设上限，避免单块超出嵌入模型或上下文窗口的承载范围。
- **minChunkSize**：功能是设定每个块的最小 Token 数；作用是过滤或合并过碎的块，防止产生信息量不足、检索价值低的碎片。
- **overlap**：功能是指定相邻块之间重复保留的 Token 数；作用是让边界内容在相邻块中延续，缓解切分点落在语义中间造成的上下文断裂。
- **keepSeparator**：功能是决定切分后是否保留分隔符；作用是维持标点和分隔结构，避免块首尾标点丢失影响可读性与语义完整性。

但 `TokenTextSplitter` 的硬伤是**分隔符策略对中文不敏感，在中文文档上容易失效**。因此它更适合作为结构信息不可用时的兜底方案，而非首选。

#### `2.1.2SentenceSplitter`：中文语义切分的核心组件

Spring AI Alibaba 提供的 `SentenceSplitter` 基于 OpenNLP 的 `SentenceDetectorME` 模型识别句子边界，再将句子按 Token 聚合为块。它不依赖硬编码的英文分隔符，**对中文文档的切分效果显著优于 TokenTextSplitter**。在中文企业文档（制度、合同、产品说明）中，应优先以 `SentenceSplitter` 作为基础切分器。

#### `2.1.3RecursiveCharacterTextSplitter`：结构优先的递归降级策略

Spring AI Alibaba 新增的递归字符切分器维护一个分隔符优先级列表（如标题 → 段落 → 句子 → 固定字符），先尝试用最粗粒度的分隔符切分，若块仍过大，再逐级下降使用更细的分隔符。这与企业级实践中“结构优先，Token 兜底”的原则一致：**语义完整性优于单纯的长度切分**。对于结构清晰的技术文档、Markdown 笔记，这是最稳妥的默认选择。

社区实践中已沉淀出更细的策略谱系：`FixedSizeSplitter`（固定字符）、`ParagraphSplitter`（段落）、`SentenceSplitter`（句子）、`SlidingWindowSplitter`（滑动窗口+重叠）、`SemanticChunkSplitter`（基于 Embedding 相似度判断语义边界）、`AgenticSplitter`（LLM 判断最佳切分点）等。但在企业级生产的**默认起点**，通常推荐递归语义分块（500 字符 / 50 重叠）或 `TokenTextSplitter`（512 Token / 重叠适度）作为基线，再根据文档类型逐步引入更专用的切分器。

### 2.2 核心参数：Chunk Size 与 Overlap 的企业级调优

**Chunk Size 的本质权衡**

Chunk Size 决定了**信息密度与语义聚焦度之间的平衡**。块越大，上下文越完整，但噪声越多，Embedding 可能因稀释而“模糊”；块越小，语义越聚焦，但可能丢失必要的背景信息，且 Embedding 模型在过短输入上表现未必最优。

企业级实践中的参考区间：

| 场景            | 推荐 Chunk Size            | 依据           |
| ------------- | ------------------------ | ------------ |
| 通用中文文档 / 技术文档 | 512 Token（约 500–1000 字符） | 多篇实践的共同基线    |
| 新闻 / 博客等短篇内容  | 256–512 Token            | 内容本身较短，无需过大块 |
| 长篇技术手册 / 制度文件 | 512–1024 Token           | 需要保留更多上下文    |
| 代码 / 结构化片段    | 尽量不按固定长度切，以结构单元为准        | 切断代码会破坏语法与缩进 |

需注意：**没有通用的“黄金数值”**，最终应通过自有业务问题的检索评估集来标定。

**Overlap 的作用与推荐值**

Overlap 解决的是**边界语义断裂**问题。当切分点恰好落在关键实体或主谓之间时，没有重叠的块会丢失主语或前提，导致检索时无法命中。Overlap 通过在相邻块间保留重复内容，让边界处的语义得以延续。

企业级实践中普遍推荐的 Overlap 范围为 **Chunk Size 的 10%–20%**。例如 512 Token 的块大小对应约 **50–100 Token** 的重叠。部分实践使用 512 Token 分块搭配 128 Token 重叠，但 128 已超过 20% 的常规上限，属于偏保守的配置，可能增加存储与重复召回的开销。一般从 **10%** 起步，观察边界召回问题后再适度上调，是更稳妥的工程路径。

一个必须遵守的硬性约束是：`chunkOverlap` 必须小于 `chunkSize`，Spring AI 的 `TokenTextSplitter` 在构造时会对此进行断言校验。


### 2.3 企业级切分的工程化关键

选对切分器只是起点。生产环境中的切分质量，取决于四个工程维度：**切分点是否服从文档结构、特殊内容是否被保护、元数据是否在切分时写全、检索与生成是否解耦**。这四个维度叠加起来，才构成企业级切分与 Demo 阶段“整篇灌进去、topK 随便截”的本质差距。

#### 2.3.1结构感知：让切分点服从文档语义边界

**为什么纯长度切分不够。** 固定字符数硬切会在两个层面制造问题：语义断层——关键判定条件、公式或步骤被割裂在两个 Chunk 中；上下文污染——单个 Chunk 包含多个不相干的子主题，稀释向量相似度得分。企业文档恰恰是结构信号最密集的场景：制度条款的“第 X 条”、合同的章/节/条、Markdown 的 H1–H4 标题、工单的段落，每一个结构边界都是一个天然的语义分界。**切分点应当优先落在这些边界上，长度约束只在结构单元仍然超长时才介入。**

**Spring AI Alibaba 中的两层实现路径。**

第一层是 `RecursiveCharacterTextSplitter` 的递归降级机制。它维护一个分隔符优先级列表，默认列表针对中文设计为 `{"\n\n", "\n", "。", "！", "？", "；", "，", " "}`，先尝试在双换行（段落）处切分，若片段仍然超长，再逐级下降用单换行、句号、感叹号、问号、分号、逗号、空格，最后才按固定字符数兜底。这意味着在结构清晰的文档上，它会**自动沿着最粗粒度的语义边界切分**，而不是一上来就按 Token 数硬截。

```
// 针对条款式文档定制分隔符优先级：条 > 款 > 段落 > 句子
RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(
    512,   // 目标块大小
    50,    // overlap
    List.of("第.*条", "\n\n", "\n", "。", "；", "，")  // 优先级从粗到细
);
List<Document> chunks = splitter.split(rawDocuments);
```

第二层是**在递归切分之前，先用自定义 DocumentTransformer 做标题树预切分**。`DocumentTransformer` 的接口是 `Function<List<Document>, List<Document>>`，可以插入 ETL 管道中 Reader 与 Splitter 之间。对于 Markdown 或条款式文档，先按标题层级建立 section 树，将每个标题下的内容作为一个独立的“结构单元”，再对仍然超长的叶子节点送入 `RecursiveCharacterTextSplitter` 做二次细分。

```
@Component
public class HeadingAwareTransformer implements DocumentTransformer {

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> sections = new ArrayList<>();
        for (Document doc : documents) {
            // 解析 Markdown 标题栈，按 H1→H4 切出 section
            List<Section> tree = parseSectionTree(doc.getContent());
            for (Section section : tree) {
                Document sectionDoc = new Document(section.content());
                // 写入标题路径 breadcrumb，供后续检索与生成使用
                sectionDoc.getMetadata().put("section_path", section.breadcrumb());
                sectionDoc.getMetadata().putAll(doc.getMetadata()); // 继承文档级元数据
                sections.add(sectionDoc);
            }
        }
        return sections;
    }
}
```

**一个容易被忽略的细节：把标题路径写进 Chunk 本身。** 结构化切分如果只切出内容、不记录“它在文档中的位置”，小 Chunk 被检索出来后模型仍然不知道它属于哪个章节。建议在 Chunk 内容顶部或元数据中写入 breadcrumb，例如 `[路径：员工手册 -> 假期管理 -> 年假]`。这样即使 Chunk 只有一两句话，模型也能从路径推断出它的适用范围和上下文。

#### 2.3.2代码与受保护块

**问题的本质。** 普通正文的切分逻辑是“在语义边界处断开”，但代码块、Mermaid/PlantUML 流程图、表格、公式的语义完整性依赖于**语法结构的不可分割性**。一个被截断的代码块丢失了缩进和闭合括号，一个被腰斩的 Mermaid 图丢失了节点关系，一个只剩半行的表格丢失了表头——这些内容一旦被“切碎”，向量检索命中的概率和生成质量会同时劣化。`RecursiveCharacterTextSplitter` 即使把代码缩进加入分隔符列表，也无法可靠地保护一个跨越数百 Token 的完整代码块。

**工程上的标准做法：placeholder 保护—还原。** 不要在切分器里硬编码“识别代码块”的逻辑，而是在切分之前先把受保护块**整体替换为占位符**，让普通切分器只处理正文；切分完成后，再把占位符还原为完整块。具体流程如下：

1. 扫描 Markdown，识别 fenced code block（\`\`\`\`\`）、table、image、Mermaid/PlantUML 块；
2. 将每个特殊块替换为带唯一 ID 的 placeholder，例如 `[[BLOCK:diagram_001]]`；
3. 对剩余正文用 `RecursiveCharacterTextSplitter` 正常分块；
4. 将 placeholder 还原为原始完整块，挂接到包含它的那个 Chunk 上；
5. 对特殊块单独生成摘要或关键词，摘要参与检索，但**最终喂给模型的必须是原始完整块**，而不是摘要。

```
public class ProtectedBlockTransformer implements DocumentTransformer {

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            ProtectedContent pc = extractProtectedBlocks(doc.getContent());
            // pc.placeholderText() 中特殊块已被替换为 [[BLOCK:xxx]]
            List<Document> textChunks = recursiveSplitter.split(
                List.of(new Document(pc.placeholderText(), doc.getMetadata()))
            );
            for (Document chunk : textChunks) {
                // 还原占位符，并将完整块作为该 chunk 的附属内容
                String restored = restoreBlocks(chunk.getContent(), pc.blockMap());
                chunk.getMetadata().put("has_protected_block", pc.containsBlock(chunk));
                result.add(new Document(restored, chunk.getMetadata()));
            }
        }
        return result;
    }
}
```

**超大受保护块的处理。** 如果单个代码块或流程图本身就超过了 Chunk 上限，**不能简单截断**，而应：保持 fenced 标记完整（拆分后每一段都补齐 \`\`\`\`\` 开头和结尾），在每一段前加上 `// [片段 1/3]` 之类的定位标记，并为该块生成一份摘要作为独立的检索入口。检索时命中摘要，回取原始完整块或与查询最相关的那一段。

#### 2.3.3元数据提前规划字段

**为什么写入时机不可逆。** 向量库中存储的最小单元是 Chunk，每个 Chunk 独立持有向量、原文和元数据。元数据不是“附加信息”，而是后续**检索过滤、权限控制、时效治理、增量更新、引用溯源**的共同基础设施。一条铁律是：**元数据没在入库切分时写全，检索期无法补回，只能全量重灌**。等到上线后才发现缺了 `department` 或 `acl_groups`，意味着所有文档必须重新解析、重新切分、重新向量化——代价远高于一开始就设计好 schema。

**六个核心字段及其用途**：

| 字段               | 用途                                                       | 来源             | 写入阶段                  | 必填性 / 缺失后果                                                 |
| ---------------- | -------------------------------------------------------- | -------------- | --------------------- | ---------------------------------------------------------- |
| `doc_id`         | 增量更新的对齐键；delete-then-add 的删除依据；对账比对的基准                   | 源文档的业务标识（非文件名） | 切分时从文档级元数据继承到每个 chunk | **必填**。缺失则无法按文档删除旧切片，增量更新退化为全量重灌；对账也无法定位差异                 |
| `department`     | 检索期按部门过滤，实现部门级知识隔离                                       | 文档归属部门的编码      | 切分时从文档级元数据继承          | **必填**（多部门场景）。缺失则部门过滤失效，用户可能召回其他部门的敏感制度                    |
| `version`        | 标识文档版本，用于增量比对和审计回滚                                       | 文档管理系统或摄入流水线   | 切分时写入，随文档变更而更新        | **强烈建议**。缺失则无法判断新旧切片是否属于同一版本，delete-then-add 缺少版本语义        |
| `effective_date` | 制度生效时间，用于时间范围过滤或“仅召回当前生效版本”                              | 文档元数据中的生效日期    | 切分时写入                 | 视业务而定。缺失则无法按生效时间筛选，新旧制度可能同时被召回                             |
| `status`         | 文档生命周期状态（如 `active` / `draft` / `archived` / `expired`）  | 文档管理系统或摄入流水线   | 切分时写入                 | **强烈建议**。缺失则检索无法排除草稿或已废止内容，模型可能基于过期制度作答                    |
| `acl_groups`     | 权限控制：检索期按用户所属组过滤，在 topK 之前拦截无权内容                         | 文档的访问控制列表      | 切分时写入                 | **必填**（有权限要求的场景）。缺失则权限过滤无法生效；旧 chunk 缺此字段时应**默认拒绝**，而非视为公开 |
| `parent_id`      | Parent-Child 回取：子块命中后，据此从父块存储取回完整上下文                     | 父块生成时分配的唯一标识   | Parent-Child 拆分时写入子块  | **使用 Parent-Child 时必填**。缺失则子块命中后无法回取父块，检索精度优势丧失且生成上下文不足    |
| `chunk_index`    | 块级溯源：标识该 chunk 在文档中的顺序位置                                 | 切分器输出顺序        | 切分时生成                 | **建议填写**。缺失则无法在引用时定位原文位置，审计溯源能力受损                          |
| `chunk_count`    | 块级溯源：标识该文档被切分的总块数                                        | 切分完成后的统计       | 切分时生成                 | 建议填写。与 `chunk_index` 配合，可判断切片是否完整、是否存在遗漏                   |
| `section_path`   | breadcrumb 标题路径（如 `员工手册 > 假期管理 > 年假`），增强小 chunk 的上下文可理解性 | 结构切分时解析的标题层级   | 结构感知切分时写入             | 建议填写（结构化文档）。缺失则短 chunk 被召回后，模型难以判断其适用范围                    |

**实现位置：在切分器的输出端统一注水，而不是分散在各个切分器中。** 建议在 ETL 管道的 Splitter 之后、Writer 之前插入一个 `MetadataEnrichmentTransformer`，集中完成元数据的继承与补全：

```
public class MetadataEnrichmentTransformer implements DocumentTransformer {

    @Override
    public List<Document> apply(List<Document> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> meta = chunk.getMetadata();
            // 继承文档级字段（doc_id, department, acl_groups, version...）
            // 若 Reader 阶段已将文档级元数据写入每个 chunk，此处可直接校验完整性
            meta.putIfAbsent("chunk_index", i);
            meta.putIfAbsent("chunk_count", chunks.size());
            meta.putIfAbsent("doc_id", meta.get("doc_id"));
            // 校验：必填字段为空则抛异常，阻止脏数据入库
            validateRequired(meta, "doc_id", "department", "acl_groups");
        }
        return chunks;
    }
}
```

**元数据 schema 要在第一批文档入库之前就定下来、过一遍评审。** 检索过滤、权限控制、时效治理、增量更新这些后面才用到的能力，依赖的字段必须提前设计进切分阶段的元数据里，不能抱着“先灌进去再说”的心态。Spring AI Alibaba 的 ETL 管道支持把多个 `DocumentTransformer` 串起来，元数据注水应该做成一个独立、可单独测试的转换器，而不是散落在各处切分逻辑里的副作用。

#### 2.3.4Parent-Child 切分：小块检索、父块喂模型

**它解决的是一个结构性矛盾。** 小 Chunk（128–256 Token）语义聚焦、检索精度高，但缺乏生成所需的上下文；大 Chunk（512–1024 Token）上下文完整，但向量被稀释、检索噪声大。固定选择任何一个尺寸，都是在“检索准”和“生成好”之间做单向妥协。Parent-Child 的做法是**把检索单元和生成单元分开**：用子块做向量索引和检索，命中后通过元数据回取父块作为生成上下文。

**存储与检索的两层设计。**

- **父块层**：较大的块（如 1024 Token 或一个完整的结构单元），**不参与向量检索**，存储在文档存储（doc store / 关系库 / 文件系统）中，以 `parent_id` 为键。它包含完整的段落或小节，是最终喂给 LLM 的上下文。
- **子块层**：父块进一步拆分的较小块（如 128 Token），**每个子块生成 Embedding 并写入向量库**，元数据中携带 `parent_id`。子块是检索面，但不直接用于生成。

检索时的流程：用户 query → 向量检索 top-K 子块 → 按 `parent_id` 去重 → 从父块存储回取完整父块 → 按父块组装 Prompt。

```
// 索引阶段：先切父块，再切子块，写入不同存储
public record ParentChildChunks(List<Document> parents, List<Document> children) {}

public ParentChildChunks buildParentChild(Document doc) {
    List<Document> parents = parentSplitter.split(List.of(doc)); // 1024 Token
    List<Document> children = new ArrayList<>();
    for (Document parent : parents) {
        String parentId = UUID.randomUUID().toString();
        parent.getMetadata().put("parent_id", parentId);
        List<Document> subs = childSplitter.split(List.of(
            new Document(parent.getContent(), parent.getMetadata())
        ));
        for (Document sub : subs) {
            sub.getMetadata().put("parent_id", parentId);
            children.add(sub);
        }
    }
    return new ParentChildChunks(parents, children);
}
```

```
// 检索阶段：子块检索 → parent_id 去重 → 回取父块
public List<Document> retrieveWithParents(String query, int topK) {
    List<Document> childHits = vectorStore.similaritySearch(
        SearchRequest.builder().query(query).topK(topK).build()
    );
    Set<String> parentIds = childHits.stream()
        .map(d -> (String) d.getMetadata().get("parent_id"))
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    return parentStore.getByIds(parentIds);  // 从 doc store 回取完整父块
}
```

**关键的工程约束。** 子块的元数据必须**完整继承父块的文档级字段**（`doc_id`、`department`、`acl_groups`、`version` 等），因为检索发生在子块层，过滤条件也是作用在子块上的。如果子块丢失了 `acl_groups`，权限过滤就会失效——这是一个隐蔽但常见的生产事故来源。另外，父块存储需要与向量库保持**同一套 doc_id 和版本语义**，增量更新时两者必须同步失效。

#### 2.3.5增量更新与切分的联动

**为什么朴素的“局部更新”不可靠。** 直觉上，文档改版时只更新变化段落对应的 Chunk 似乎最经济。但 Chunk 的边界不是稳定的：在文档前部插入一段文字，后面所有 Chunk 的边界都会偏移，“第 3 个 Chunk”在新旧版本中可能完全对应不同的内容。基于位置或 diff 的局部更新，在边界偏移面前会制造新旧切片混杂、检索结果错位的隐患。**企业级场景下更可靠的语义是：以文档为单位整体替换，而不是以 Chunk 为单位局部打补丁。**

**delete-then-add：以 doc_id 为对齐键的整体换血。**

当一份文档发生变更时，执行：

1. 用 `doc_id` 删除该文档的所有旧 Chunk（向量库 + 父块存储 + 摘要索引，三处同步）；
2. 对新版本重新执行完整的切分流水线（结构切分 → 保护块处理 → 元数据注水 → Parent-Child 拆分）；
3. 写入新 Chunk，携带新的 `version` 和 `effective_date`。

Spring AI 的 `VectorStore` 提供了两种删除入口：按文档 ID 列表删除 `delete(List<String> idList)`，以及按元数据过滤表达式删除 `delete(Filter.Expression filterExpression)`。按 `doc_id` 删除时，既可以先查出该文档的全部 Chunk ID 再批量删除，也可以直接构造 `doc_id == "xxx"` 的过滤表达式删除。后者在向量库支持 filter-delete 的情况下更简洁，但需要注意部分实现（如 `SimpleVectorStore`）对 filter 删除的支持有限，生产环境应优先选用支持按元数据删除的向量库后端。

```
public void reingest(Document newVersion) {
    String docId = (String) newVersion.getMetadata().get("doc_id");

    // 1. 删除旧切片：向量库、父块存储、摘要索引同步清理
    vectorStore.delete(new FilterExpressionBuilder().eq("doc_id", docId).build());
    parentStore.deleteByDocId(docId);
    summaryIndex.deleteByDocId(docId);

    // 2. 重新切分 + 元数据注水（新 version）
    List<Document> chunks = pipeline.transform(List.of(newVersion));

    // 3. 写入新切片
    vectorStore.write(chunks);
    parentStore.write(parentsOf(chunks));
}
```

**定时对账作为第二道保险。** 事件驱动的 webhook 在理想情况下可以即时触发重新入库，但多数企业的文档系统并不具备可靠的变更事件能力。因此生产环境通常需要叠加一层**每日对账**：对比文档系统的 `doc_id + version` 清单与向量库中的 `doc_id + version` 清单，找出“有文档但向量库缺失”“version 落后”“文档已删除但切片仍在”三类差异，对差异项执行 delete-then-add 或删除。对账的产出同时可以生成知识运营报表：新增了什么、更新了什么、哪些文档长期未更新。

**切分器本身不负责增量逻辑，但它产出的元数据决定了增量是否可实施。** `doc_id` 必须在切分时写入每一个 Chunk（包括子块），`version` 必须随文档变更而更新；如果这两个字段在入库时缺失或写错，delete-then-add 就失去了对齐键，对账也无从谈起。

#### 2.3.6五个子节之间的关系可以这样理解：

2.3.1 和 2.3.2 解决“切在哪里”，2.3.3 解决“切出来的 Chunk 携带什么”，2.3.4 解决“检索用哪个、生成用哪个”，2.3.5 解决“文档变了怎么换血”。四者共同构成一个可运维的企业级切分流水线，而不是一组孤立的切分参数。

---

## 三、权限过滤

### 3.1 为什么不能靠 Prompt

有人第一反应是：在 System Prompt 里写「你只能回答用户有权知道的制度」不就行了？

不行，两个原因。

**第一，越权数据一旦进了 Prompt，泄露就已经发生了。** 模型看到了不该看的内容，哪怕它「答应」不说，你也可以用「忽略之前的所有指令」把它套出来。安全边界不能建在模型的自觉上。

**第二，模型没有权限概念。** 它不知道「财务部」和「HR」在你们公司是什么关系，也不知道「密级 B」意味着什么。这些是你的业务知识，不是它的。

正确的位置只有一个：**检索层**。在向量检索的 `filterExpression` 里，让它压根检不出来。

### 3.2 两种写法：功能等价，安全性不等价

Spring AI 的 `SearchRequest` 支持两种过滤表达式写法：

```java
// 写法一：字符串，SQL 风格
SearchRequest.builder()
        .query("年假多少天")
        .topK(4)
        .similarityThreshold(0.35)
        .filterExpression("dept == 'hr' && version == '2025'")
        .build();

// 写法二：编程式 DSL
var b = new FilterExpressionBuilder();
SearchRequest.builder()
        .query("年假多少天")
        .filterExpression(b.and(b.eq("dept", "hr"), b.eq("version", "2025")).build())
        .build();
```

两种写法最终都会变成同一个 `Filter.Expression` 对象——字符串那种在内部由 `FilterExpressionTextParser` 解析成表达式树。所以功能上是等价的。

但**安全性上不等价**。看下面这段：

```java
// 千万别这么写
String filter = "dept == '" + userInputDept + "'";
```

|                                |   |                                 |
| ------------------------------ | - | ------------------------------- |
| 如果 `userInputDept` 传进来的是 \`hr' |   | dept == 'finance\`，拼出来的表达式就变成了： |

```text
dept == 'hr' || dept == 'finance'
```

一条过滤器，把你的部门隔离直接拆了。这跟 SQL 注入是同一类问题，只是靶子从数据库换成了向量库。

所以规则很简单：**用户输入永远不进字符串表达式；要用，就走 `FilterExpressionBuilder`，走参数位。** 更稳的做法是压根不让用户输入参与过滤——只允许在「用户本来就能看的范围」里收窄，不给扩大口径的机会：

```java
private static final Set<String> VALID_DEPTS = Set.of("hr", "finance", "all");

// 请求里想切部门可以，但只认和登录态一致的值；
// 对不上就退回登录态里的真实部门，绝不能 fallback 到 "all"（那等于越权）
String dept = VALID_DEPTS.contains(raw) && raw.equals(authenticatedDept)
        ? raw
        : authenticatedDept;
```

### 3.3 权限的三条铁律

**铁律一：服务端拼，前端传的一律不信。**

前端传个 `{"dept": "hr"}`，服务端就信了，等于门口保安问“你哪个部门的？”，访客自己说“我是 HR”，保安就放行。——**访客自己填的表，永远不能当身份证用**。

**正确做法：** 身份只能从服务端已经验过的地方拿，比如登录态、SSO token、网关下发的可信用户头。拿一个服务端认的 `userId`，再去用户目录查这个人到底属于哪个部门、什么角色、能看哪些组。前端最多只能说“我想在 HR 范围内再筛一下”，而且必须和登录态对得上才认；对不上就退回登录态里的真实部门，**绝不能退到“全部可见”**。

**铁律二：入库时就要带 scope，检索时才滤得动。**

检索的时候过滤条件写得特别漂亮——“只查财务部能看的”。结果入库时财务部这个标签压根没贴。那查询时就两种结局：要么把没贴标签的全滤掉，该看的也看不着；要么加一句“没标签的也算全员可见”兜底，结果**不该看的全放出来了**。第二种更吓人，因为系统看起来还能用，实际上已经漏成筛子。

**为什么不能事后补：** 因为向量库里存的是一个个切好的小片段，每个片段自己带着标签。查询时只能看已经贴好的标签，**没贴的它不知道、也补不上**。要补，只能把整份文档重新解析、重新切、重新算向量——等于全量重灌，代价大得多。

**铁律三：过滤条件要能覆盖「全员可见」这类中间态。**

|                                                                                                                 |   |                                         |
| --------------------------------------------------------------------------------------------------------------- | - | --------------------------------------- |
| 制度文档不是“要么只有 HR 能看，要么所有人都能看”这么简单。员工手册全员可见，差旅标准只对要出差的人有意义，薪酬细则只有 HR 和财务能看。如果只用一个“部门”字段硬编码，很快就会变成到处复制 \`部门 == 'hr' |   | 部门 == 'all'\`。复制多了必然有人漏写、有人写错，权限模型就开始烂。 |

**正确做法：分三层写门禁。**

- **最外层是“租户边界”，谁都绕不过。** 不管你是谁、什么角色，先保证你只能看自己公司的资料。这一条写死在最外面，因为跨公司泄露是出大事的级别，不能靠里面任何条件兜底。
- **中间层是“角色”。** 知识库管理员可以只看租户边界，不用再叠加部门条件；普通用户就得继续往下走。
- **最内层是“本部门 或 全员可见”。** 用一句 `or` 把“本部门”和“全员可见”显式并起来，这样既不是“只能看本部门”，也不是“全都能看”，而是两者的并集。

**为什么顺序不能反：** 部门写错，最坏是“看到了同事的制度”；角色写错，最坏是“管理员看到本租户内不该看的”；租户写错，是“看到别家公司的制度”。越严重的边界越要放最外层、越不能绕过。这样即使里面某层有 bug，外面那层还兜得住。

### 3.4 时间与版本

「这条制度去年就改了」——这是企业 FAQ 最高频的投诉。解决办法不是让模型去判断新旧，而是让旧版本检不出来。

两种做法，各有取舍：

| 做法       | 实现                                              | 优点        | 缺点                 |
| -------- | ----------------------------------------------- | --------- | ------------------ |
| **版本字段** | metadata 带 `version`，检索时 `version == '2026-01'` | 简单，切灰度容易  | 每个请求都要带当前版本号       |
| **生效期**  | 带 `effective_from` / `effective_to`，检索时比较当前日期   | 调用方不用关心版本 | 日期比较依赖向量库对字符串比较的支持 |

生效期方案有个细节要注意：**日期一定用 ISO 格式（`2026-01-01`）存字符串**，这样字典序比较等于时间比较。存成 `2026/1/1` 就废了——`2026/10/1` 会排在 `2026/2/1` 前面。要更稳妥就存 epoch 毫秒数，走数值比较，代价是可读性差。

### 3.5 多租户

多租户和部门权限看着像，实际不是一个东西。部门权限错了，是「看到了同事的制度」；租户边界错了，是「看到了别家公司的制度」，直接上法庭。

工程上的差别是：**租户条件必须是硬编码在最外层的，不能由任何请求参数决定，也不能有「如果没传就跳过」的分支**。做 SaaS 的话，我甚至建议租户 id 从 `ThreadLocal`/请求上下文里读，连方法参数都不给。

---

## 四、混合检索（Hybrid）

### 4.1 什么时候必须上混合检索：向量是“认脸”，关键词是“认身份证号”

**向量检索的强项是“换个说法也能找到”。** 你问“怎么请假”，它能召回“请假流程是什么”，因为它比的是**意思像不像**。这是它的主场。

但它有个硬伤：**对“不像人话”的东西不敏感。** 看几个典型翻车场景：

- **制度编号 `A-2025-017`**：编号本身没什么“意思”，`016` 和 `017` 在向量空间里几乎贴在一起，经常串号；
- **产品型号、SKU**：同理，而且这些词可能压根不在模型训练时见过；
- **人名、部门缩写**：缩写和全称的向量距离未必近，“HR”和“人力资源部”不一定靠得拢；
- **精确短语（比如“三期”）**：意思相近的说法太多，向量容易被同义改写带偏。

而企业知识库里，**编号、条款号、系统名、型号到处都是**。所以结论很干脆：**只要你的语料里有一堆专名和编号，就必须上关键词召回，或者干脆两路一起上（Hybrid）。**

打个比方：向量检索像**认脸**——长得像就认出来，但双胞胎容易认错；关键词检索像**认身份证号**——号码对不上就是不对，一个数字都不含糊。企业文档里既有“脸”也有“身份证号”，所以两种认法都得有。

### 4.2 RRF：为什么用名次而不是分数

两路召回都回来了，怎么合？

最直觉的做法是“向量分 0.82 + BM25 分 12.4，加权平均一下”。**但这两个分数根本不是一个量纲**——向量分是 0 到 1 的相似度，BM25 分可能是 0 到几十的相关性打分。你拿 0.82 和 12.4 相加，就像拿“体温 37 度”加“体重 65 公斤”，加出来的数没有意义。权重怎么调都是玄学，换个 embedding 模型就得重调一遍。

**RRF（Reciprocal Rank Fusion）绕开了这个坑：不比分数，只比名次。**

公式是 `score(d) = Σ 1 / (k + rank_i(d))`，`k` 一般取 60。

翻译成人话：**一个文档在每一路召回里排第几名，就按名次给分；排名越靠前，分越高；两路都排得靠前的，分数叠加，被顶上来。**

举个例子：

- 文档 A 在向量里排第 1，在关键词里排第 1 → 得分 `1/61 + 1/61`，两路都认可，**顶到最前**；
- 文档 B 只在向量里排第 1，关键词里没进榜 → 得分只有 `1/61`，排在 A 后面。

这正是我们想要的效果：**两路都认可的文档优先。** 一路说好可能是巧合，两路都说好才靠谱。

`k=60` 也不是什么神秘数字，就是原论文的经验值：`k` 越大，名次之间的差距被压得越平，融合越“温和”；`k` 越小，头部效应越强。60 是个不容易出错的起点。

**RRF 最漂亮的地方在于：它只吃名次，不管底下用什么引擎。** 你今天用 2-gram，明天换 Elasticsearch，后天换向量库自带的全文检索——融合逻辑一行都不用改。

### 4.3 一个必须避开的坑：关键词那一路也要过权限

**场景是这样的：** 向量那一路规规矩矩带了权限过滤，只查用户有权看的。关键词那一路为了“快”，直接在全量倒排索引里搜，**没带权限过滤**。结果财务同事通过关键词召回，把 HR 的制度捞出来了。而且因为关键词命中的往往是原文措辞，模型引用起来还特别自信，**比纯向量越权更难被发现**——纯向量越权可能还会因为“意思不太对”露馅，关键词越权是原文照搬，看起来特别可信。

**规则只有一条：权限过滤是检索层的属性，不是某一路召回的属性。两路必须用同一份规则。**

但这两份逻辑重复了，是个隐患——**改了一处忘了另一处，就是下一个事故**。生产上建议把它们收敛到一个 `ScopePolicy` 里，由它同时产出表达式和谓词；实在懒得抽，至少写个单测把两边对齐。

打个比方：**大门和小门都得查证件。** 你不能因为“小门走得快”就不查，否则从小门溜进来的人，比从大门闯进来的还危险。

### 4.4 中文关键词的坑

英文做 BM25，按空格分词就完事了。中文没有空格，得另想办法。

教学代码里用了一个**很土但够用**的办法：**2-gram + ASCII 整词加权**。

- **中文部分切成两字窗口**：“年假多少天”切成 年假 / 假多 / 多少 / 少天，命中算 1 分；
- **ASCII 串整段保留当一个词**：`A-2025-017` 整体匹配才算命中，算 3 分。

为什么编号要整段匹配、给更高权重？因为**编号拆开就没意义了**——`A-2025-017` 拆成 `A2`、`20`、`02` 全是噪声，反而会干扰排序。整段匹配才说明“就是它”。

这套东西在**几百条文档**上够用。上了**万级**就该换 Elasticsearch / OpenSearch 的 BM25，或者用向量库自带的全文检索能力。但**融合逻辑不用改**——RRF 只吃名次，底下换引擎对它没影响。这就是 RRF 设计得漂亮的地方：**上层融合和底层引擎解耦**，换引擎像换轮胎，不用重新造车。

---

## 五、引用溯源

### 5.1 为什么“让模型标注 doc_id”行不通：模型不是记不住，就是会瞎编

最初的写法通常是 Prompt 里写一句“每条结论后标注 `[来源:doc_id]`”。听着挺合理，跑起来就发现两个坑：

**第一个坑：模型记不住随机字符串。** `hr-leave` 和 `fin-017` 在它眼里就是“一串字母数字”，抄错一个字符你根本发现不了。就像让一个人背下十串随机验证码再默写出来，错一位太正常了。

**第二个坑：模型会编。** 尤其是检索结果少的时候，它觉得“总得给个来源吧”，就凭空造一个看起来特别合理的 `doc_id` 出来。你拿去一查——查无此文。

所以引用这件事**不能交给模型**。正确的分工是：**模型只负责说“这句话来自第 1 条和第 3 条资料”**，编号是短整数，抄错的概率低得多；就算抄错了，也只是少引用一条，不会指向一个不存在的文件。**编号到文档的映射，由后端来做。**

### 5.2 正确姿势：从响应里把“这次实际用了哪些文档”捞出来

Spring AI 的两个 RAG Advisor 都会把“这次实际用了哪些文档”写回响应元数据。这是官方留的口子，比自己维护一个 Map 靠谱得多。

| Advisor                        | 常量                    | 实际字符串值                   |
| ------------------------------ | --------------------- | ------------------------ |
| `QuestionAnswerAdvisor`        | `RETRIEVED_DOCUMENTS` | `qa_retrieved_documents` |
| `RetrievalAugmentationAdvisor` | `DOCUMENT_CONTEXT`    | `rag_document_context`   |

用法就是回答完之后，从 `ChatResponse` 的元数据里把 `List<Document>` 捞出来。拿到这份列表，`doc_id`、`title`、`chunk_index`、`score` 全在里面，`sources` 数组直接就能组出来。

**关键原则：以这份后端列表为准，模型在正文里标了什么只当增强。** 模型标对了是锦上添花，标错了也不影响来源列表的准确性。

### 5.3 编号协议：给前端一个能点的东西

企业 FAQ 的引用不是给人看的装饰，是要**能点开、能跳转、能高亮**的。所以返回结构里这六个字段，一个都别省：

```
public record Source(
        int no,             // 编号，和正文里的 [1] 对应
        String docId,       // 业务主键
        String title,       // 标题，前端展示
        Integer chunkIndex, // 第几段，用来定位原文
        String snippet,     // 片段，截断到 120 字
        Double score) {}
```

逐项说：

- **`no`**：正文里写 `[1]`，前端拿这个 `no=1` 去对，就能把引用和来源挂上钩；
- **`docId`**：业务主键，点开跳转到哪份文档靠它；
- **`title`**：给人看的标题；
- **`chunkIndex`**：这是第几段，前端可以“跳到原文第 N 段”并高亮；
- **`snippet`**：截断到 120 字左右的片段，够人判断“是不是这条”；
- **`score`**：相似度分，调试和排序用。

**`snippet` 一定要截断。** 整段 chunk 塞进 JSON，一个回答返回十几 KB，前端渲染卡、日志也不好查。120 字左右够人判断“是不是这条”就行。

**`chunkIndex` 是白送的东西。** `TextSplitter` 切分时会自动补这个字段，不用白不用，用上它前端就能做“跳到原文第 N 段”。

还有一个细节值得做：**拼给模型的片段，本来就该带编号。** 这样模型只需要“引用编号”，不需要“描述来源”，输出更稳。`ContextualQueryAugmenter` 提供了 `documentFormatter` 专门干这个：

```
ContextualQueryAugmenter.builder()
        .documentFormatter(docs -> numbered(docs))   // [1] 《标题》第 2 段\n正文...
        .build()
```

**通俗说：** 这就像给每份资料贴上“1 号、2 号、3 号”的标签再递给模型，模型只需要说“我用了 1 号和 3 号”，不用去描述“那份讲年假的 HR 文件”。标签是后端贴的，模型只管报号。

### 5.4 一个反直觉的结论

引用做得再仔细，也不要指望模型 100% 标对。实测下来，模型漏标（该标没标）比错标常见，而漏标是可以接受的——用户看到答案，下面挂着三条可点的来源，体验已经成立。真正不能接受的是**来源列表为空但答案很具体**，那说明它不是「看着资料答」的。

所以判断一次回答可不可信，不看模型标了几个 `[1]`，看 `sources` 是不是空的。这也是为什么第十节案例的返回值里，`grounded` 字段是独立算的，而不是让模型填。

---

## 六、回答不知道是一条产品需求，不是免责声明

### 6.1 空上下文是最危险的输入

RAG 最反直觉的一点：**检索不到，比检索到不相关的更危险。**

检索到不相关的，模型还有机会说「资料里没提到」；检索为空，你给它的 Prompt 还是那句「请根据以下资料回答」，它面前摆着一个空白的资料区和一个明确的任务——大多数模型会选择「尽力而为」。

所以空结果必须被显式处理，不能靠 Prompt 里的「如果不知道就说不知道」硬扛。

### 6.2 `ContextualQueryAugmenter` 默认就帮你拦了

Spring AI 的模块化 RAG 在这件事上做得挺到位。`ContextualQueryAugmenter` 的默认配置是 `allowEmptyContext = false`，文档为空时它不是「什么都不加」，而是**把用户问题整段换掉**：

```text
The user query is outside your knowledge base.
Politely inform the user that you can't answer it.
```

模型收到的已经不是一个「待回答的问题」，而是一条「告诉用户你不知道」的指令。这比在 Prompt 里加一句约束有效得多——因为它把「回答」这个任务本身撤销了。

想换成自己的话术，用 `emptyContextPromptTemplate`：

```java
ContextualQueryAugmenter.builder()
        .allowEmptyContext(false)
        .emptyContextPromptTemplate(new PromptTemplate("""
                知识库中没有与该问题相关的制度条款。
                请用一句话告知用户：知识库暂未收录，建议联系 HR 或行政确认，不要猜测。
                """))
        .build();
```

这里有个硬约束要记住：**`promptTemplate` 必须保留 `{query}` 和 `{context}` 两个占位符**。源码里有一句 `PromptAssert.templateHasRequiredPlaceholders(this.promptTemplate, "query", "context")`，少了哪个，应用启动时直接抛异常。自定义模板时踩这个坑的人不少，报错信息还挺隐晦。

### 6.3 三档降级，按场景选

| 档位          | 做法                      | 适合              | 代价           |
| ----------- | ----------------------- | --------------- | ------------ |
| **A. 直接拒答** | 空 → 固定话术，不调模型           | 制度、合规、医疗等错不起的场景 | 用户体验略硬       |
| **B. 放宽重试** | 阈值 0.6 空 → 降到 0.35 再搜一次 | 语料表述不规范，怕漏召回    | 多一次检索；可能招来噪声 |
| **C. 澄清追问** | 空 → 「您是想问 A 还是 B？」      | 问题太短、指代不明       | 要多一轮交互       |

我默认选 A。B 的风险在于：第二次检索拿回来的往往是「勉强相关」的内容，模型照样会基于它作答，等于用降级把幻觉请回来了。真要用 B，记得在返回里标一个 `confidence=low`，让前端有个提示。

### 6.4 阈值别脑袋一拍随便填，用一个土办法标定

`similarityThreshold` 设 0.35 还是 0.7，取决于你的 embedding 模型、语料长度、问题风格，抄别人的值基本没意义。有个二十行代码就能做完的标定方法：

1. 准备 20 条**域内**问题（知识库确实能答的），20 条**域外**问题（闲聊、竞品、公司根本没有的信息）；
2. 全部跑一遍检索，只取 top1 的分数；
3. 把两组分数排开看分布——域内的应该整体高于域外，中间那道「沟」就是阈值。

沟不明显，说明问题不在阈值：要么 chunk 切得不对（信息被切断，分数普遍偏低），要么 embedding 模型不适合你的语料（中文长文本尤其常见）。**换模型比调阈值有用。**

还有个容易被忽略的事实：`similarityThreshold` 是**客户端后置过滤**，源码注释写得很清楚：

> Note that this is a post-processing step performed on the client not the server side.

翻译成大白话就是：

- **向量库（server side）**&#x4F1A;照常做它该做的事——计算查询向量与库里所有（或过滤后的）候选向量的相似度，排好序，然后把前 `topK` 个结果**原封不动地**交还给 Spring AI；
- **Spring AI 在客户端（client side）**&#x62FF;到这批结果之后，再看一眼你的 `similarityThreshold`，把分数低于这个值的条目**删掉**，剩下的才返回给你的业务代码。

所以，流程不是“向量库只计算高分结果”，而&#x662F;**“向量库全算完、全排序、全返回；客户端再自己砍一刀”**。**想省成本要调 `topK`**，不是调阈值。

---

## 七、Agent + RAG：三种接法，以及身份带入

### 7.1 三种接法

| 模式             | 机制                       | 优点              | 缺点                      |
| -------------- | ------------------------ | --------------- | ----------------------- |
| **Advisor 注入** | 每次对话自动检索、拼 Prompt        | 代码最少，路径固定，行为可预测 | 不问也检索；闲聊也白跑一次 embedding |
| **RAG Tool**   | Agent 自己决定是否调 `searchKb` | 灵活，可挂多个库、多套策略   | 多一轮工具调用；模型可能「懒得搜」       |
| **Graph 节点**   | 固定「先检索 → 再生成」的边          | 可控、可审计、可插 HITL  | 不如 Agent 灵活             |

选型口诀：

- **每个问题都必须查库** → Advisor 或 Graph 固定节点；
- **有时闲聊、有时查制度** → Tool；
- **合规要求「必须先检索再回答」，且要留审计轨迹** → Graph。

别小看第一行。「不问也检索」听起来是浪费，但对纯 FAQ 场景反而是优点：行为确定，延迟稳定，测试好写。反过来，客服场景里一半是「你好」「谢谢」，全走检索就是纯烧钱。

### 7.2 用 Tool 的时候，最大的坑是身份

它是 Demo 代码和生产代码差距最大的地方。

最直觉的写法是把部门当参数让模型填：

```java
// 反面教材
@Tool(description = "检索制度片段，必须传部门")
public String searchKb(String query, String dept) { ... }
```

问题很明显：`dept` 是模型填的。它可能填错，也可能被用户的一句「我是 HR 总监」带偏。**这等于把权限交给了 Prompt。**

正确做法是把身份放在模型看不见的地方。Spring AI Alibaba 1.1.2.2 里的链路是这样的：

```text
RunnableConfig.addMetadata("user_id", ...)
        ↓
AgentToolNode: ToolCallRequest.context(config.metadata())
        ↓
toolContextMap = builder.toolContext + req.getContext()
        ↓
new ToolContext(toolContextMap) → 工具方法签名里的 ToolContext 参数
```

翻一下 `AgentToolNode.executeToolCallWithInterceptors` 就能看到：

```java
ToolCallRequest request = ToolCallRequest.builder()
        .toolCall(toolCall)
        .context(config.metadata().orElse(new HashMap<>()))   // ← metadata 进上下文
        .build();
...
Map<String, Object> toolContextMap = new HashMap<>(toolContext);
toolContextMap.putAll(req.getContext());                      // ← 合进 ToolContext
```

所以调用侧只要这么写：

```java
RunnableConfig config = RunnableConfig.builder()
        .threadId(threadId)
        .addMetadata("user_id", userId)     // 来自登录态，模型看不到，也改不了
        .addMetadata("thread_id", threadId)
        .build();

agent.invoke(question, config);
```

工具侧就能拿到：

```java
@Tool(description = "检索制度片段。只传问题原文，不要传部门或身份信息。")
public String searchKb(@ToolParam(description = "用户问题原文") String query, ToolContext ctx) {
    String userId = String.valueOf(ctx.getContext().get("user_id"));
    UserScope scope = userDirectory.scopeOf(userId);   // 服务端解析权限
    ...
}
```

模型的工具签名里只有 `query` 一个参数，它**没有能力**指定部门。想伪造也没有入口——`user_id` 不是它填的。

顺便纠正一个很常见的写法错误。SpringAi底座篇里 ChatClient 注册工具是这么写的：

```java
chatClient.prompt().user(q).tools(new WeatherTools())   // ChatClient：可以直接给 POJO
```

换到 `ReactAgent` 上照抄，编译就过不去——`ReactAgent.Builder` 的 `.tools(...)` 只接受 `ToolCallback` 和 `List<ToolCallback>`，带 `@Tool` 注解的 POJO 必须走另一个方法：

```java
.methodTools(new WeatherTools())   // ReactAgent：POJO → ToolCallbacks.from(...)
```

同一个框架里两套写法，坑了不少人。要么一开始就把工具建成 `FunctionToolCallback`（ReactAgent 生产实践那篇就是这么干的），要么记住「ReactAgent 用 `methodTools`」。

### 7.3 Instruction 怎么写

工具给了，还得让模型愿意用。Instruction 要写得明确，不能开放式。

原文给了一段示例，核心是五条：

1. 涉及考勤、假期、报销等，**先调 `searchKb` 再回答**；
2. **只依据检索结果回答**，不补充片段之外的内容；
3. 每个结论用 `[1]`、`[2]` 标注编号，编号必须来自本次检索；
4. 检索结果为空或片段里没答案，**回答“知识库暂未收录”**，禁止猜测；
5. 与公司制度无关的问题，直接简短回答，不必检索。

原文总结了四条经验，都是血泪教训：

- **工具名要和 Instruction 里写的一字不差。** 方法叫 `searchKb`，Instruction 里写 `search_kb`，模型会去找一个不存在的工具——**不报错，直接放弃调用**。这种“静默失效”最难查，因为日志里什么都没发生。
- **“必须”比“建议”有用。** 写“你可以检索”，模型在长对话里会忘，尤其当它觉得自己知道答案时。
- **给一个固定的空信号。** 工具返回空列表和返回字符串 `EMPTY`，对模型来说是两件事：前者它容易忽略，后者是一个明确的、需要处理的结果。**用 `EMPTY` 这个词。**
- **别在 Instruction 里写业务细节。** “年假 10 天”这种事实应该只在知识库里出现一次。写进 Prompt 就等于开了第二个信息源，两边不一致时，**模型会挑那个更顺口的**——它可不管哪个才是最新版。

### 7.4 工具该返回什么：结构化一点，带编号带标题

返回裸文本也能跑，但结构化一点更好：

```
[1] 《员工考勤与假期管理办法》第 1 段
第 1 条 年假
司龄满 1 年不满 3 年，年假 10 天；满 3 年不满 5 年，12 天；满 5 年起 15 天。

[2] 《员工手册（通用条款）》第 3 段
第 3 条 假期
年假、病假、婚假等具体天数见各部门《假期管理办法》，本手册不重复规定。
```

三个好处：

- **模型引用编号时有据可依**——它看到 `[1]` 就在旁边，引用起来不容易错；
- **从日志里能直接看出“它到底看到了什么”**——排查问题时不用猜；
- **出问题时不用猜是检索错了还是模型读错了**——看返回内容就知道。

打个比方：**给模型的东西要像一份带页码的参考资料，而不是一团没有结构的文字。** 有页码（编号）、有标题（文档名）、有段落号，模型引用起来稳，你排查起来也快。

---

## 八、知识库更新

### 8.1 注意SimpleVectorStore 不能按表达式删

**场景是这样的：** 制度文档改了，你想把旧版本从向量库里删掉。直觉写法是按业务字段删：

```java
vectorStore.delete("doc_id == 'hr-leave'");
```

在 `SimpleVectorStore` 上，这行代码会抛 `UnsupportedOperationException`。

原因在 `AbstractObservationVectorStore` 里：

```java
protected void doDelete(Filter.Expression filterExpression) {
    // this is temporary until we implement this method in all concrete vector stores
    throw new UnsupportedOperationException();
}
```

`doDelete(Filter.Expression)` 是个模板方法，默认实现就是抛异常，生产级的向量库（Milvus、PGVector、Redis、Elasticsearch）各自覆盖了这个方法，所以能按条件删。但 `SimpleVectorStore` 不行。

### 8.2 所以需要一张台账

**解决思路很朴素：** 既然向量库只认 id，那我自己记着“哪份文档对应哪些 id”就行了。

```java
/** doc_id -> 该文档当前版本的 chunk id 列表 */
private final Map<String, List<String>> chunkIdsByDoc = new ConcurrentHashMap<>();

public int ingest(FaqDoc doc) {
    List<String> old = chunkIdsByDoc.getOrDefault(doc.docId(), List.of());
    if (!old.isEmpty()) {
        vectorStore.delete(old);      // 先删旧版本
        keywordIndex.remove(old);     // 关键词索引也要删，别漏
    }
    ...
    chunkIdsByDoc.put(doc.docId(), chunks.stream().map(Document::getId).toList());
    return chunks.size();
}
```

**关键设计：台账的 key 是 `doc_id`，value 是“这一轮实际生成的 id 列表”。** 为什么 key 不能用 chunk id？因为 chunk id 不稳定——文档改一个字，重新切分后所有 chunk id 都可能变。但 `doc_id` 是稳定的业务主键，文档改了它不变。所以用稳定的 `doc_id` 去索引不稳定的 chunk id 列表，重灌一次，台账就换一份。

**注意这张台账不能放内存里。** 放内存里，服务一重启，台账就没了，旧 chunk 从此变成“孤儿向量”——永远不会被删掉，还照样被检索出来，用户就会看到新旧两版混杂的答案。**必须放 Redis 或数据库，跟摄入流水线同一个事务。**

**关键词索引也要删。** 第四节这里是混合检索场景，向量库和关键词索引是两套存储，删的时候两边都要动，漏一边就是“向量删了但关键词还能搜到旧内容”。

### 8.3 版本灰度怎么做

新制度不是「一键切换」的，通常要并行跑一段时间。两种做法：

| 做法                   | 实现                                      | 适合        |
| -------------------- | --------------------------------------- | --------- |
| **单索引 + version 过滤** | 新旧两版 chunk 同时存在，`version` 字段区分，请求里带目标版本 | 灰度范围小，切换快 |
| **双索引 + 别名切换**       | 新旧各一个索引，别名指向当前生效的                       | 全量切换，回滚干净 |

单索引的好处是简单，坏处是「同时存在两份几乎一样的文本」，检索时容易互相干扰——尤其当两版只改了一句话，向量几乎一样，模型会拿到两条矛盾的片段。这时候 `version` 过滤必须严格。

### 8.4 缓存要按版本失效

RAG 一次问答的成本不低：要跑 embedding、要查向量库、要把片段拼进 Prompt、还要调一次大模型。如果同一个问题、同一套权限的人反复问，每次都重新跑一遍，纯属浪费。

所以很自然想到**缓存**：第一次问“年假多少天”，把答案存起来；第二个人再问同样的问题，直接把存好的答案返回，不调模型、不查库。又快又省钱。

所以缓存不是随便存的，它有一个 **key（键）**。每次来一个问题，系统先用这个 key 去缓存里查：**key 一样，就说明是同一个问题，直接返回缓存的答案；key 不一样，就重新算。**

所以 key 的设计决定了“什么算同一个问题”。如果 key 只写 `question`，那“年假多少天”这个字符串一样，就永远命中同一条缓存。

**问题来了：制度更新了，key 却没变**

假设缓存 key 是 `hash(question)`，只包含问题文本。

- 1 月 1 日，有人问“年假多少天”，系统检索到旧制度，回答“满 5 年 15 天”，存进缓存；
- 3 月 1 日，制度改了，满 5 年改成 20 天，知识库重新摄入；
- 3 月 2 日，又有人问“年假多少天”——**问题文本一模一样，key 一模一样，缓存直接命中**，返回的还是“15 天”。

**制度已经更新了，用户看到的还是旧答案。** 这就是“缓存不按版本失效”的典型事故。

**我们可以这样设计：key 里加上知识库版本号**

```
cacheKey = hash(question + tenant + dept + roles + kbVersion)
```

这一行代码的意思是：**把这几样东西拼在一起，算出一个指纹，作为缓存的 key。** 逐项解释：

| 组成          | 含义         | 为什么必须在 key 里                |
| ----------- | ---------- | --------------------------- |
| `question`  | 用户问的问题文本   | 不同问题不能共用缓存                  |
| `tenant`    | 租户         | 不同公司的知识库不同，A 公司的答案不能给 B 公司  |
| `dept`      | 部门         | 不同部门看到的制度可能不同，HR 的答案不能给财务   |
| `roles`     | 角色         | 管理员和普通员工可见范围不同，答案可能不同       |
| `kbVersion` | **知识库版本号** | **制度更新后，这个值变了，旧 key 自然对不上** |

**最关键的是 `kbVersion`。** 它不需要很复杂，就是一个**全局单调递增的整数**：每次摄入新文档、更新文档，就把它 `+1`。

- 1 月 1 日，`kbVersion = 100`，缓存 key 里带的是 100；
- 3 月 1 日，制度更新，摄入完成，`kbVersion` 变成 101；
- 3 月 2 日再问“年假多少天”，拼出来的 key 里 `kbVersion = 101`，**跟缓存里的 100 对不上**，缓存不命中，系统重新检索、重新生成，拿到新答案。

**为什么用“版本号 +1”，而不是“主动删缓存”**

你可能会问：制度更新了，为什么不直接把相关缓存删掉？

因为**很难知道哪些缓存受影响**。一份文档改了，可能影响几十个问题的答案，而这些问题的缓存 key 散落在各处，靠人工或逻辑去推断“哪些 key 该删”，既复杂又容易漏。漏删一条，就是一次旧答案事故。

而“版本号 +1”的做法是**一刀切**：所有旧 key 里的 `kbVersion` 都停在旧值，新请求拼出来的 key 永远带新值，**旧缓存自动全部作废**，不用你主动删任何东西。旧缓存留在那里等过期时间（TTL）到了自然回收就行。

---

## 九、全链路检查点总结

先看整条链路长什么样：

```text
文档 → 解析 → 切分（结构 / 保护块） → 元数据注水 → 双写（向量库 + 关键词索引）
                                                       │
用户提问 → 身份解析 → 权限表达式 ─┬→ 向量召回 ─┐
                                 └→ 关键词召回 ┴→ RRF 融合 → 阈值截断
                                                       │
                                       编号化拼 Prompt → 生成 → sources
```

这条链上真正决定效果的只有三个环节：

| 环节          | 决定什么                    | 出问题后能不能补救        |
| ----------- | ----------------------- | ---------------- |
| 入库（第二、八节）   | 效果**上限**：这段内容到底能不能被搜到   | 不能，只能全量重灌        |
| 检索（第三、四节）   | 效果**下限**：该出的出没出、不该出的出没出 | 能，改过滤和召回策略       |
| 生成（第五、六、七节） | 表达稳不稳、来源立不立得住           | 能，改 Prompt 和返回结构 |

### 上线前照着这两张表打勾

按链路顺序过一遍。第一张是文档入库时只做一次的——**做错不可逆，先看它**；第二张是每次提问都会走的，错了还能改。

**表一：入库侧（一次做对，后面才能治）**

| 步骤       | 该做什么                                                                                              | 漏了会怎样                                   |
| -------- | ------------------------------------------------------------------------------------------------- | --------------------------------------- |
| 1. 解析    | 先变成干净文本：扫描件做 OCR，页眉页脚、水印、目录清掉                                                                     | 扫描件等于全文空白，怎么搜都搜不到；页眉混进正文，每块都带着「内部资料」的噪声 |
| 2. 选切分器  | 中文用 `SentenceSplitter` 或递归字符切分，`TokenTextSplitter` 只当兜底                                           | 它只认英文标点，中文段落只能按字数硬截，「第 3 条」从中间断开        |
| 3. 定切点   | 优先落在「第 X 条」、标题、章节这些天然边界，长度只在超长时介入                                                                 | 一块塞进三个条款，向量被平均成「谁都不像」，问哪条都匹配不上          |
| 4. 留路径   | 把 `员工手册 > 假期管理 > 年假` 写进块内容或 `section_path`                                                        | 一两句话的块被召回，模型不知道它管谁，答出来张冠李戴              |
| 5. 护住特殊块 | 代码、表格、流程图先换成占位符，切完再还原；单块超长就标「片段 1/3」并另做摘要                                                         | 表格只剩半行、代码丢了右括号，既搜不准也答不对                 |
| 6. 写元数据  | `doc_id` / `department` / `acl_groups` 必填，`version` / `status` / `chunk_index` / `source_uri` 建议写 | 删不掉旧切片、过滤不了权限、引用跳不回原文——**这一步没做，事后补不了**  |
| 7. 卡校验   | 必填字段为空直接拒绝入库，别抱着「先灌进去再说」的心态                                                                       | 脏数据进了库，查询期补不回来，只能整库重灌                   |
| 8. 记台账   | `doc_id → 这一版所有 chunk id` 落库，关键词索引那份一起记                                                           | 服务一重启台账就没了，旧切片变成孤儿向量，新旧两版答案混着出          |
| 9. 双写一致  | 向量库和关键词索引要么同一个引擎，要么有对账 / 补偿任务                                                                     | 向量删了、关键词还在，用户能搜到已经废止的制度                 |
| 10. 换版方式 | 以文档为单位「删旧写新」，别按块打补丁；灰度用 `version` 过滤或双索引加别名                                                       | 文档前面插一段，后面所有块边界全偏移已经是别的内容               |

**表二：运行时（每次提问按顺序走）**

| 步骤        | 该做什么                                                                                | 漏了会怎样                                       |
| --------- | ----------------------------------------------------------------------------------- | ------------------------------------------- |
| 1. 认人     | 身份从登录态 / SSO / 网关下发的用户头取，请求体里的 `userId`、`dept` 一律不认                                 | 改个 userId 就能看别人的制度                          |
| 2. 拼权限    | 服务端拼、走 `FilterExpressionBuilder`；租户写死最外层，再角色，再「本部门 or 全员可见」                         | 一个引号就能把部门隔离拆了：`dept == 'hr'` 后面被追加上「or 财务部」 |
| 3. 两路召回   | 语料里有编号、型号、条款号，就必须加一路关键词                                                             | `A-2025-016` 和 `017` 向量几乎重合，问 016 答成 017    |
| 4. 两路同权   | 关键词那一路用同一份规则（同一个 `ScopePolicy`）                                                     | 从小门溜进来的原文照搬，比向量越权更像真的、更难发现                  |
| 5. 融合     | RRF 按名次算 `1/(60+rank)`，别把两路分数加权平均                                                   | 0.82 和 12.4 不是一个量纲，权重怎么调都是玄学，换个模型还得重调       |
| 6. 截阈值    | 用 20 条域内 + 20 条域外问题找分数分布的「沟」，并记住它是客户端后置过滤                                           | 抄来的 0.7 让你一条都搜不到；放到 0.2 又把噪声全放进来            |
| 7. 重排（可选） | 融合后取 topK×2 交给 Cross-Encoder 或 gte-rerank，再截到 topK                                  | 「差不多相关」的片段直接进上下文，答案看着对、细节偏                  |
| 8. 编号     | 片段进 Prompt 就带 `[1] 《标题》第 N 段`，模型只报号                                                 | 让模型背 `doc_id`，它记不住还会编，引用指向不存在的文件            |
| 9. 取来源    | `sources` 从响应元数据捞（`qa_retrieved_documents` / `rag_document_context`），`grounded` 后端算 | 模型标了几个 `[1]` 不等于有依据，审计拿不出证据                 |
| 10. 空就拒答  | `allowEmptyContext=false`，把「回答」这个任务本身撤掉                                             | 检索为空时模型一定会编，而且编得最像真的                        |
| 11. 身份进工具 | 工具签名里不出现 `dept`，身份走 `RunnableConfig.metadata` → `ToolContext`                       | 等于把权限交给 Prompt，用户说一句「我是 HR 总监」就能带偏          |
| 12. 约束指令  | 工具名一字不差、写「必须检索」、空结果返回 `EMPTY`                                                       | 名字写错就静默不调用，日志里什么都看不到，这种问题最难查                |
| 13. 缓存    | key 带 `tenant` / `dept` / `roles` / `kbVersion`                                     | 3 月制度改了，用户还在看 之前的答案                         |
| 14. 留痕评估  | 检索日志（query + 权限 + 命中 docId）+ 固定评测集回归                                                | 改一次参数不知道是变好还是变坏，只能等用户投诉                     |

表一里最麻烦的是第 6、8 行：**它们的错误在上线当天不会报错，只会在三个月后某次制度改版时集中爆发。**

最后记住顺序：**入库决定上限，检索决定下限，生成只决定观感。** 效果不好，先回头看切分和元数据，再去改过滤和召回，最后才轮得到 Prompt。反过来做，等于在漏水的地基上刷墙。

---

## 十、案例：企业 FAQ Agent

场景：一家公司有四个制度文档，员工分 HR、财务两个条线，另有一个知识库管理员。要求是权限内能答、权限外说不知道、答案带可点的来源、制度改版后立刻生效。

三个端点：

```text
POST /api/s71/ask          Agent 链路（模型自己决定要不要检索）
POST /api/s72/ask-advisor  Advisor 链路（固定先检索再答）
POST /api/s73/ingest       覆盖式入库（同 docId 再灌 = 换版本）
```

主链路是这样跑的：

```text
HTTP(userId, question)
   │
   ├─ userId ──► 用户目录 ──► UserScope(dept, roles, tenant)
   │                                │
   │                                ├─► Filter.Expression  (向量召回)
   │                                └─► Predicate          (关键词召回)
   │
   ├─ RunnableConfig.metadata{user_id, thread_id}
   │
   └─► ReactAgent
         └─ searchKb(query)  ── ToolContext 里读 user_id，模型看不到
               ├─ 向量召回 topK*2（带过滤）
               ├─ 关键词召回 topK*2（带过滤）
               ├─ RRF 融合 → topK
               └─ 编号化 → [1]《标题》第N段 + 正文
                     │
                     ├─ 有命中 → 模型回答 + [1][2]
                     └─ 空     → EMPTY → 固定拒答话术
```


### 10.1 知识资产层：切分、入库、台账、关键词索引

三段代码里的注释统一用两种标记，看到标记就知道该怎么对待它：

| 标记     | 含义                     | 你要做什么          |
| ------ | ---------------------- | -------------- |
| `[教学]` | 为了让这个 Demo 单文件跑起来而做的简化 | 别照抄，知道它在偷什么懒就行 |
| `[生产]` | 上生产必须替换的地方，写清楚换成什么、为什么 | 照着改，改完要有运维手段兜底 |

```java
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
```

### 10.2 检索层：权限 + 混合召回

```java
package space.kmind.zk.springaialibabalearning.stage07;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 用户的数据可见范围：一次问答里「这个人能看什么」的唯一事实来源。
 *
 * 做成 record 是有意的——不可变对象传进检索层之后，没有任何地方能偷偷改它（见 3.3）。
 *
 * [教学] 三个字段、一个写死的租户，够演示三层门禁。
 * [生产] tenant 从租户上下文取，dept / roles 从员工系统或 SSO token 解析；
 *        如果要按组授权，再加一个 acl_groups（文档侧 ACL ∩ 用户所属组）。
 *        另外：filter() 和 allows() 现在是两处手写规则，改一处忘一处就是越权事故，
 *        生产请收敛成一个 ScopePolicy 同时产出表达式和谓词，并写单测对齐两者（见 4.3）。
 */
public record UserScope(String userId, String dept, Set<String> roles) {

    /**
     * 给向量库用的过滤表达式：租户 → 角色 →（本部门 or 全员可见）。
     *
     * 顺序不能反：部门写错最坏是「看到了同事的制度」，租户写错是「看到别家公司的制度」（见 3.3）。
     *
     * [教学] 租户写死 "acme"。
     * [生产] 租户条件必须由拦截器 / 框架统一注入，方法签名里最好别出现它——
     *        一旦可以「不传」，就一定会出现「忘了传 → 不过滤」的那天。
     */
    public Filter.Expression filter() {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        if (roles.contains("kb_admin")) {
            return b.eq("tenant", "acme").build();              // 管理员：只守租户边界
        }
        return b.and(
                b.eq("tenant", "acme"),                          // 租户永远在最外层
                b.or(b.eq("dept", dept), b.eq("dept", "all"))    // 本部门 或 全员可见
        ).build();
    }

    /**
     * 给内存关键词索引用。语义必须和 filter() 完全一致，否则关键词那一路就是后门。
     *
     * 注意这里是「默认拒绝」：字段缺失时 String.valueOf(null) 得到 "null"，
     * 既不等于 "acme" 也不等于 "all"，结果是拒绝——老数据缺字段时不会误判成公开（见 2.3.3）。
     *
     * [教学] 手写谓词，因为教学用的关键词索引是内存实现，吃不下 Filter.Expression。
     * [生产] 关键词也交给 ES 之后这份谓词就会消失，两路共用同一套过滤规则，这是最省心的结局；
     *        在那之前，务必写单测断言 filter() 和 allows() 对同一批文档给出相同结果。
     */
    public boolean allows(Document d) {
        Map<String, Object> m = d.getMetadata();
        if (!"acme".equals(m.get("tenant"))) {
            return false;
        }
        if (roles.contains("kb_admin")) {
            return true;
        }
        String deptOfDoc = String.valueOf(m.get("dept"));
        return dept.equals(deptOfDoc) || "all".equals(deptOfDoc);
    }
}
```

```java
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
```


### 10.3 装配层：ReactAgent + Tool + 三个接口

```java
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

        seed();   // [生产] 删掉：启动即灌库只适合教学，真实语料走 /api/s73/ingest 或独立流水线
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
```

```
package space.kmind.zk.springaialibabalearning.stage07;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为 stage07 提供内存版 VectorStore Bean（教学用）。
 * 生产请换成 Redis / Milvus 等向量库 starter 的自动配置。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}

```



### 10.4 自测：五个请求，五种行为

**① HR 问年假（正例，看引用）**

```json
POST /api/s71/ask
{"userId":"u1001","threadId":"t1","question":"年假多少天？"}
```

期望：答案里出现「10 天 / 12 天 / 15 天」，并带 `[1]`；`sources[0].docId = "hr-leave"`，`grounded = true`。

**② 财务问年假（权限隔离）**

```json
POST /api/s71/ask
{"userId":"u2002","threadId":"t2","question":"年假多少天？"}
```

期望：**拿不到 HR 的具体天数**。能检索到的只有《员工手册》里那句「具体天数见各部门《假期管理办法》」，所以答案应该是「通用条款未给出具体天数，建议咨询 HR」——这不是拒答，是「照着能看到的资料回答」。`sources` 里只会有 `all-handbook`。

如果这里出现了「10 天」，说明过滤条件漏了，先查 `UserScope.filter()` 和入库时的 `dept` 字段。

**③ 问知识库里没有的（拒答）**

```json
POST /api/s71/ask
{"userId":"u1001","threadId":"t3","question":"公司股票代码是多少？"}
```

期望：`sources = []`，`grounded = false`，回答是「知识库暂未收录，建议联系 HR 或行政确认」。这条验证的是 `EMPTY` 信号 + Instruction 第 4 条有没有生效。

**④ 按编号查（Hybrid 的价值）**

```json
POST /api/s71/ask
{"userId":"u2002","threadId":"t4","question":"A-2025-016 对发票原件有什么要求？"}
```

期望：`sources[0].docId = "fin-016"`，答案是「须提供发票原件照片，电子发票需同时提交 PDF 原件，截图不予受理」。

把关键词召回临时关掉（`corpus.keywordSearch` 返回空列表）再跑一次，你会发现 `fin-017` 更容易挤进前列——两段文本都在讲报销，向量分不开「016」和「017」。这就是 Hybrid 存在的理由。

**⑤ 改制度（版本替换）**

```json
POST /api/s73/ingest
{"docId":"hr-leave","title":"员工考勤与假期管理办法","dept":"hr","version":"2026-01",
 "effectiveFrom":"2026-01-01",
 "text":"第 1 条 年假\n司龄满 1 年不满 3 年，年假 12 天；满 3 年不满 5 年，15 天；满 5 年起 20 天。\n第 2 条 病假\n病假需提供二级以上医院证明，当月累计超过 3 天的，需部门负责人审批。"}
```

再问一次「年假多少天」：

```json
POST /api/s71/ask
{"userId":"u1001","threadId":"t5","question":"年假多少天？"}
```

期望：答案是「12 天 / 15 天 / 20 天」，且**不会再出现「10 天」**。如果新旧答案同时出现，说明旧 chunk 没删干净——回去看 `chunkIdsByDoc` 那张台账，或者直接查向量库里 `doc_id == 'hr-leave'` 的条数是不是翻倍了。

> 注意：重灌之后 `chunk_index` 会重新从 0 开始，这是正常的。但 `sources` 里的 `chunkIndex` 只能定位到「新版文档的第 N 段」，如果前端要做原文跳转，还需要一个 `source_uri` 和文档版本号一起传回去。
>
> 另外说明一下本例的选择：这里用的是「删旧写新」而不是「version 过滤」。两条路都行，但别混着用——本例走删除，是因为 `SimpleVectorStore` 不支持按表达式删、只支持按 id 删，台账刚好派上用场；如果你的向量库支持 `delete(Filter.Expression)`，也可以保留多版本、靠 `version` 字段切换，那时台账就从「删除依据」变成了「审计记录」。

### 10.5 从 Demo 到生产，还差这些

前面两张表里的每一项，落到具体组件上就是这份替换清单。左边是案例代码里现在用的，右边是生产该换的，每一种 Demo 写法在某个具体场景下都会翻车。

| 项     | Demo 里是什么                    | 生产换成什么，为什么                                                                 |
| ----- | ---------------------------- | -------------------------------------------------------------------------- |
| 解析    | 直接喂纯文本                       | 加 `DocumentReader` + OCR：扫描件不做 OCR，等于往库里灌了一堆空白                             |
| 切分    | `TokenTextSplitter` 一把梭      | 按标题 / 条款切（`SentenceSplitter`、递归切分）+ 人工抽检：中文硬切会把条款拦腰截断                      |
| 台账    | 内存 `Map`                     | Redis / 数据库，与摄入同事务：重启不丢、多实例共享，旧切片才删得掉                                      |
| 检索证据  | 内存 `Map<threadId, hits>`     | 落库并带 requestId：多实例不串号，审计能回答「这个答案当时看到了什么」                                   |
| 关键词索引 | 内存 2-gram + 手拍权重             | Elasticsearch / OpenSearch 的 BM25：上万条扛得住，IDF 自带权重，中文交给 analyzer            |
| 向量库   | `SimpleVectorStore`          | Milvus / PGVector / DashVector：先确认它实现了 `delete(Filter.Expression)`，否则还得靠台账 |
| 召回方式  | 两路串行调用                       | 两路并行（`CompletableFuture`）：延迟从「两路之和」降到「较慢的那一路」                              |
| 重排    | 无                            | `DocumentPostProcessor` 接 Cross-Encoder 或百炼 gte-rerank：把「差不多相关」挡在上下文之外     |
| 身份    | 请求体传 `userId`                | `SecurityContext` / 网关下发的用户头：前端能改的东西不能当身份证用                                |
| 权限规则  | `filter()` 和 `allows()` 各写一份 | 收敛成一个 `ScopePolicy`，两边都由它产出：两处手写，迟早改漏一处                                    |
| 会话记忆  | `MemorySaver`                | Redis / 数据库的 `CheckpointSaver`：服务一重启，多轮对话全断                                |
| 提示词   | 常量写死在类里                      | 配置中心 / 提示词平台，带版本号：改一句话不用发版，改坏了能回滚                                          |
| 缓存    | 无                            | Redis，key 带租户 / 部门 / 角色 / `kbVersion`：不缓存白烧钱，但是缓存要做版本隔离                    |
| 灰度    | 无                            | 单索引 + `version` 过滤，或双索引 + 别名：新制度先小范围生效，出问题一秒切回                             |
| 对账    | 无                            | 每天比一次「文档系统的 doc_id + version」和「向量库的 doc_id + version」：漏推、漏删、版本落后都能捞出来      |
| 限流与成本 | 无                            | 按用户 / 租户限流 + 超时 + 单次检索条数上限：一个爬虫就能把 embedding 额度刷完                          |
| 评估    | 手点几个问题                       | 固定评测集（问题 + 应命中的 doc_id + 应有答案），每改一次配置跑一遍回归                                 |
| 观测    | 只有应用日志                       | 检索链路 tracing + 命中率 / 拒答率 / 引用命中率：出问题能一眼看出是切分、检索还是生成的锅                      |

把左边这列连起来看，它们都建立在三个假设上：**只有几十份文档、只有几个人在问、只有一台机器在跑**。生产里这三条一条都不成立。

---

## 下一篇预告

下一阶段 **MCP**：把工具从「写死在 Agent 里的 Java 方法」变成「标准协议后面的可插拔 Server」，让 ChatClient、ReactAgent、Graph 节点共用同一批能力。本篇的 `searchKb` 到那时候就不再是注解方法，而是一个 MCP Server 暴露的 tool。

---

## 官方参考

- 模块化 RAG / Playground：<https://java2ai.com/docs/1.0.0.2/practices/usecase/playground/>
- Spring AI 检索增强生成（含 `RetrievalAugmentationAdvisor`、`VectorStoreDocumentRetriever`、`ContextualQueryAugmenter`）
- Spring AI 向量数据库（过滤表达式语法、`FilterExpressionBuilder`）
- 源码位置（1.1.2）：
  - `spring-ai-rag/.../rag/advisor/RetrievalAugmentationAdvisor.java`（`DOCUMENT_CONTEXT`）
  - `advisors/spring-ai-advisors-vector-store/.../QuestionAnswerAdvisor.java`（`RETRIEVED_DOCUMENTS`、`FILTER_EXPRESSION`）
  - `spring-ai-commons/.../transformer/splitter/TokenTextSplitter.java`（断句字符、默认参数）
  - `spring-ai-vector-store/.../observation/AbstractObservationVectorStore.java`（`doDelete(Filter.Expression)` 默认抛异常）
- Spring AI Alibaba 源码位置（1.1.2.2）：
  - `spring-ai-alibaba-agent-framework/.../agent/node/AgentToolNode.java`（`RunnableConfig.metadata` → `ToolContext`）
  - `spring-ai-alibaba-agent-framework/.../agent/tools/ToolContextConstants.java`
