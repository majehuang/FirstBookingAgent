package io.agentharness.tui.render;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamBufferTest {

    @Test
    void 没有换行时全部留在尾巴里_一行都不落地() {
        StreamBuffer.Append result = StreamBuffer.empty().append("你好");

        assertThat(result.completedLines()).isEmpty();
        assertThat(result.buffer().tail()).isEqualTo("你好");
        assertThat(result.buffer().hasPending()).isTrue();
    }

    @Test
    void 跨多次append的一行在遇到换行时才落地() {
        StreamBuffer buffer = StreamBuffer.empty();
        buffer = buffer.append("你").buffer();
        buffer = buffer.append("好").buffer();
        StreamBuffer.Append result = buffer.append("世界\n");

        assertThat(result.completedLines()).containsExactly("你好世界");
        assertThat(result.buffer().hasPending()).isFalse();
    }

    @Test
    void 一次append含多个换行时按顺序落地多行() {
        StreamBuffer.Append result = StreamBuffer.empty().append("第一行\n第二行\n第三行未完");

        assertThat(result.completedLines()).containsExactly("第一行", "第二行");
        assertThat(result.buffer().tail()).isEqualTo("第三行未完");
    }

    @Test
    void 空行被保留_模型用空行分段时不能被吃掉() {
        StreamBuffer.Append result = StreamBuffer.empty().append("段一\n\n段二\n");

        assertThat(result.completedLines()).containsExactly("段一", "", "段二");
    }

    @Test
    void CRLF被规整为LF() {
        StreamBuffer.Append result = StreamBuffer.empty().append("一行\r\n");

        assertThat(result.completedLines()).containsExactly("一行");
    }

    @Test
    void flush把未完成的尾巴作为最后一行落地() {
        StreamBuffer buffer = StreamBuffer.empty().append("最后一行没有换行").buffer();
        StreamBuffer.Append flushed = buffer.flush();

        assertThat(flushed.completedLines()).containsExactly("最后一行没有换行");
        assertThat(flushed.buffer().hasPending()).isFalse();
    }

    @Test
    void 尾巴为空时flush不产生空行() {
        assertThat(StreamBuffer.empty().flush().completedLines()).isEmpty();
    }

    @Test
    void 空delta不改变缓冲() {
        StreamBuffer buffer = StreamBuffer.empty().append("abc").buffer();

        assertThat(buffer.append("").buffer()).isEqualTo(buffer);
        assertThat(buffer.append(null).buffer()).isEqualTo(buffer);
    }
}
