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