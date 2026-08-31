package io.agentharness.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code XINFO} 返回值的解析。
 *
 * <p>单独测它，是因为这段解析<b>决定了 INV-2d 的硬拦截会不会被绕过</b>：
 * {@code ConsumerJanitor} 靠 {@code pending = 0} 来拦住删除，
 * 而 pending 读不出来时如果当成 0，一个还挂着工作的 consumer 就会被删掉，
 * 那些工作随即无声蒸发。
 */
class XInfoTest {

    @Test
    @DisplayName("RESP2 的扁平数组能转成字段表")
    void 解析扁平数组() {
        Map<String, Object> fields = XInfo.toFields(
                List.of("name", "pod-a", "pending", 3L, "idle", 5000L));

        assertThat(fields).containsEntry("name", "pod-a")
                .containsEntry("pending", 3L)
                .containsEntry("idle", 5000L);
    }

    @Test
    @DisplayName("RESP3 的 Map 直接认")
    void 解析Map() {
        Map<String, Object> fields = XInfo.toFields(
                Map.of("name", "pod-b", "pending", 0L));

        assertThat(fields).containsEntry("name", "pod-b").containsEntry("pending", 0L);
    }

    @Test
    @DisplayName("认不出的形态返回空表，而不是抛异常把整个清理带停")
    void 未知形态不抛异常() {
        assertThat(XInfo.toFields("一个字符串")).isEmpty();
        assertThat(XInfo.toFields(null)).isEmpty();
    }

    @Test
    @DisplayName("INV-2d 数值解析失败时返回空，绝不退化成 0")
    void 解析失败不退化成零() {
        assertThat(XInfo.number(7L)).hasValue(7L);
        assertThat(XInfo.number("7")).hasValue(7L);
        assertThat(XInfo.number(" 7 ")).hasValue(7L);

        // 这三条是关键：返回 0 的话，pending 检查会直接放行，
        // 一个还挂着工作的 consumer 就被删了，工作无声蒸发
        assertThat(XInfo.number(null)).isEmpty();
        assertThat(XInfo.number("不是数字")).isEmpty();
        assertThat(XInfo.number(List.of())).isEmpty();
    }
}
