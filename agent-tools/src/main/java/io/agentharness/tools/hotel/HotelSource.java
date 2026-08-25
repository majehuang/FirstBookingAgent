package io.agentharness.tools.hotel;

import java.util.List;
import java.util.Optional;

/**
 * 酒店业务数据源。
 *
 * <p><b>这个接口是阻塞的</b>，真实实现会走 JDBC 或内部 HTTP 接口。
 * 调用方必须自己处理 offload：middleware 里的阻塞调用会占死事件循环线程，
 * 症状是全 pod 所有 session 的吞吐一起掉到个位数（INV-7）。
 * 工具内部则不需要手动 offload —— {@code ToolExecutor} 已经默认跑在 boundedElastic 上。
 */
public interface HotelSource {

    /** 按 id 批量查询。查不到的 id 直接不出现在结果里，不抛异常。 */
    List<Hotel> lookup(List<String> hotelIds);

    /** 数据时间。卡片内容冻结落库，UI 必须标出它，否则用户会把三天前的房价当成今天的。 */
    String dataAsOf();

    default Optional<Hotel> find(String hotelId) {
        return lookup(List.of(hotelId)).stream().findFirst();
    }
}
