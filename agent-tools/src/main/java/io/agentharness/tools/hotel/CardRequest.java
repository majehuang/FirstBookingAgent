package io.agentharness.tools.hotel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 卡片请求的入参策略。**工具与 middleware 共用同一份判断**，否则两边会对"什么算合法"产生分歧：
 * middleware 认为合法就去查库，工具认为不合法就拒绝，白查一次。
 *
 * <p>三条策略都是<b>确定的</b>，不做静默处理：
 * <ul>
 *   <li><b>空列表 → 拒绝。</b>静默生成一张没有条目的空卡片，用户看到的是一个空框，
 *       而模型以为自己已经展示过了，接着就会说"这几家里我推荐…"</li>
 *   <li><b>超上限 → 拒绝，不截断。</b>静默截断意味着 {@code shown} 摘要与用户实际看到的对不上，
 *       模型会引用一家根本没显示出来的酒店</li>
 *   <li><b>重复 id → 去重保留首次。</b>拒绝也是一种选择，但重复引用通常是模型的小失误，
 *       去重比打回去更省一轮交互；关键是结果确定</li>
 * </ul>
 */
record CardRequest(List<String> hotelIds, String error) {

    /** 一次最多展示几张。上限在这里，Schema 表达不了数组长度，只能靠描述提示模型 + 这里兜住。 */
    static final int MAX_CARDS = 5;

    boolean valid() {
        return error == null;
    }

    static CardRequest of(List<String> rawIds) {
        List<String> cleaned = dedupe(rawIds);

        if (cleaned.isEmpty()) {
            return new CardRequest(List.of(),
                    "没有可展示的酒店：hotelIds 为空。请先用 search_hotels 查询，再把结果里的 id 传进来。");
        }
        if (cleaned.size() > MAX_CARDS) {
            return new CardRequest(List.of(),
                    "一次最多展示 " + MAX_CARDS + " 家，收到 " + cleaned.size()
                            + " 家。请挑选最相关的 " + MAX_CARDS + " 家再调用。");
        }
        return new CardRequest(cleaned, null);
    }

    /** 去重保留首次出现的顺序。顺序要稳定 —— 卡片内容是要冻结落库并逐字节比对的。 */
    private static List<String> dedupe(List<String> rawIds) {
        if (rawIds == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : rawIds) {
            if (id != null && !id.isBlank()) {
                unique.add(id.strip());
            }
        }
        return List.copyOf(new ArrayList<>(unique));
    }
}
