package io.agentharness.keys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyNamespaceTest {

    @Nested
    @DisplayName("键名快照 —— 这些值一旦改变就是数据迁移")
    class Snapshots {

        /**
         * 固定 sessionId 必须永远算出同一个键名。
         *
         * <p>这不是普通断言：这些字面量锁住的是**线上已经存在的 key**。
         * 改了分片算法、分片数、hash tag 格式或者分隔符，这条就会红 ——
         * 红了不要改测试，要意识到你正在做的是数据迁移（INV-9）。
         */
        @ParameterizedTest(name = "{0} → 分片 {1}")
        @CsvSource({
                "s-local,            224",
                "abc,                 11",
                "u-echo-01,           46",
                "0,                  175",
                "session-0000000001,  17",
                "会话-中文,           222"
        })
        void 分片结果被锁定(String sessionId, int expectedShard) {
            assertThat(KeyNamespace.shardOf(sessionId)).isEqualTo(expectedShard);
        }

        @Test
        void 七类key的完整形态() {
            String sid = "s-local";

            assertThat(KeyNamespace.inbox(sid)).isEqualTo("{s224}:sess:s-local:inbox");
            assertThat(KeyNamespace.outbox(sid)).isEqualTo("{s224}:sess:s-local:outbox");
            assertThat(KeyNamespace.cursor(sid)).isEqualTo("{s224}:sess:s-local:cursor");
            assertThat(KeyNamespace.lease(sid)).isEqualTo("{s224}:sess:s-local:lease");
            assertThat(KeyNamespace.state(sid)).isEqualTo("{s224}:sess:s-local:state");
            assertThat(KeyNamespace.ctrlStream(sid)).isEqualTo("{s224}:sess:s-local:ctrl-stream");
            assertThat(KeyNamespace.READY).isEqualTo("ready");
        }

        @Test
        @DisplayName("ready 刻意不分片 —— 每个 pod 只监听一条 stream")
        void ready没有hash_tag() {
            assertThat(KeyNamespace.READY).doesNotContain("{").doesNotContain("}");
        }

        @Test
        void 分片数固定为256() {
            assertThat(KeyNamespace.SHARD_COUNT).isEqualTo(256);
        }
    }

    @Nested
    @DisplayName("同槽保证")
    class SameSlot {

        @Test
        @DisplayName("同一 session 的所有 key 必须同槽 —— 摘牌脚本的前提（INV-2）")
        void 同一session的key共用hash_tag() {
            String sid = "abc";
            String tag = KeyNamespace.hashTag(sid);

            assertThat(KeyNamespace.inbox(sid)).startsWith(tag);
            assertThat(KeyNamespace.lease(sid)).startsWith(tag);
            assertThat(KeyNamespace.sameSlot(KeyNamespace.lease(sid), KeyNamespace.inbox(sid))).isTrue();
            assertThat(KeyNamespace.sameSlot(KeyNamespace.state(sid), KeyNamespace.ctrlStream(sid))).isTrue();
        }

        @Test
        void 不同session通常不同槽() {
            assertThat(KeyNamespace.sameSlot(
                    KeyNamespace.inbox("abc"), KeyNamespace.inbox("s-local"))).isFalse();
        }

        @Test
        @DisplayName("ready 与任何 session key 都不同槽 —— 投递必然是两条命令（INV-1）")
        void ready与session_key不同槽() {
            assertThat(KeyNamespace.sameSlot(KeyNamespace.READY, KeyNamespace.inbox("abc"))).isFalse();
        }
    }

    @Nested
    @DisplayName("分片边界与分布")
    class Sharding {

        @Test
        void 分片永远落在0到255之间_不会因为哈希为负而出现负数() {
            // Integer.MIN_VALUE 那条路径是这类代码最经典的坑，用大量样本把它压出来
            for (int i = 0; i < 50_000; i++) {
                int shard = KeyNamespace.shardOf("sess-" + i);
                assertThat(shard).isBetween(0, KeyNamespace.SHARD_COUNT - 1);
            }
        }

        @Test
        void 全部256个分片都能被取到() {
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < 50_000 && seen.size() < KeyNamespace.SHARD_COUNT; i++) {
                seen.add(KeyNamespace.shardOf("sess-" + i));
            }
            assertThat(seen).hasSize(KeyNamespace.SHARD_COUNT);
        }

        @Test
        void 分布不能明显倾斜_否则热点会集中在少数槽上() {
            int[] histogram = new int[KeyNamespace.SHARD_COUNT];
            int total = 100_000;
            for (int i = 0; i < total; i++) {
                histogram[KeyNamespace.shardOf("sess-" + i)]++;
            }
            int ideal = total / KeyNamespace.SHARD_COUNT;
            for (int count : histogram) {
                // 放宽到理想值的 ±50%：再宽就失去意义，再窄会因为哈希的正常波动而偶发失败
                assertThat(count).isBetween(ideal / 2, ideal * 3 / 2);
            }
        }

        @Test
        void hash_tag是三位零填充_保证前缀定长() {
            // 不定长前缀会让运维按前缀扫描时漏掉一部分 key
            for (int i = 0; i < 5_000; i++) {
                assertThat(KeyNamespace.hashTag("sess-" + i)).hasSize(6).matches("\\{s\\d{3}}");
            }
        }
    }

    @Nested
    @DisplayName("sessionId 校验")
    class Validation {

        @ParameterizedTest(name = "拒绝 {0}")
        @ValueSource(strings = {"a:b", "a{b", "a}b", "a b", "a\tb", "a\nb"})
        void 含结构字符的id被拒绝(String sessionId) {
            // 放进一个冒号就能让 a:b 与 a + b 撞成同一个 key
            assertThatThrownBy(() -> KeyNamespace.inbox(sessionId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("非法字符");
        }

        @Test
        void 空或超长的id被拒绝() {
            assertThatThrownBy(() -> KeyNamespace.inbox(null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> KeyNamespace.inbox("  "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> KeyNamespace.inbox("x".repeat(129)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("超长");
        }

        @Test
        void 常见合法id放行() {
            assertThat(KeyNamespace.inbox("s-local")).isNotBlank();
            assertThat(KeyNamespace.inbox("user_001.session")).isNotBlank();
            assertThat(KeyNamespace.inbox("会话-中文")).isNotBlank();
        }
    }
}
