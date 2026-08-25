package io.agentharness.tools.hotel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版数据源，带可配置延迟。
 *
 * <p>延迟不是为了"更真实"，是为了让 INV-7 的违反<b>能被测出来</b>：
 * 没有延迟的话，把阻塞调用放在事件循环线程上跑也看不出任何异常。
 */
public final class InMemoryHotelSource implements HotelSource {

    private final Map<String, Hotel> byId = new LinkedHashMap<>();
    private final Duration latency;
    private final String dataAsOf;
    private final AtomicLong lookups = new AtomicLong();

    public InMemoryHotelSource(List<Hotel> hotels, Duration latency, String dataAsOf) {
        hotels.forEach(hotel -> byId.put(hotel.id(), hotel));
        this.latency = latency == null ? Duration.ZERO : latency;
        this.dataAsOf = dataAsOf;
    }

    /** 演示用的固定数据集。 */
    public static InMemoryHotelSource demo() {
        return new InMemoryHotelSource(List.of(
                new Hotel("h-guomao", "北京国贸大酒店", "¥1,280", "4.8★", "含双早"),
                new Hotel("h-hilton", "王府井希尔顿", "¥1,050", "4.7★", "步行 12 分钟"),
                new Hotel("h-atour", "东直门亚朵", "¥680", "4.6★", "性价比高"),
                new Hotel("h-jinjiang", "锦江之星西站店", "¥320", "4.2★", "近高铁")),
                Duration.ZERO, "2026-08-24 10:00");
    }

    @Override
    public List<Hotel> lookup(List<String> hotelIds) {
        lookups.incrementAndGet();
        sleepQuietly();

        List<Hotel> found = new ArrayList<>();
        if (hotelIds != null) {
            for (String id : hotelIds) {
                Hotel hotel = byId.get(id);
                if (hotel != null) {
                    found.add(hotel);
                }
            }
        }
        return List.copyOf(found);
    }

    @Override
    public String dataAsOf() {
        return dataAsOf;
    }

    /** 查询次数。用来断言"重开会话不重查"。 */
    public long lookupCount() {
        return lookups.get();
    }

    private void sleepQuietly() {
        if (latency.isZero()) {
            return;
        }
        try {
            Thread.sleep(latency.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
