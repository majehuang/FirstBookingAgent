package io.agentharness.store.datasource;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * 取 {@link DataSource} 的唯一入口 —— **整个项目不允许在别处 new 连接池**。
 *
 * <p>这个接口存在的理由只有一个：后续要把这些模块集成进现有的 servlet 项目，
 * 那边已经有自己的数据源（连接池、监控、故障转移都挂在上面）。
 * 到时候只要 {@code DataSourceProvider.of(现有的 DataSource)}，
 * 存储层一行代码都不用改，也不会在同一个进程里跑出第二个池来。
 *
 * <p>开发期用 {@link SimpleDataSourceProvider}，它自带一个够用的小池子。
 */
public interface DataSourceProvider extends AutoCloseable {

    DataSource dataSource();

    /** 集成点：直接使用外部注入的数据源，生命周期由注入方负责，这里不接管、不关闭。 */
    static DataSourceProvider of(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return new DataSourceProvider() {

            @Override
            public DataSource dataSource() {
                return dataSource;
            }

            @Override
            public void close() {
                // 外部注入的数据源由外部关闭。在这里关掉会把宿主应用的池一起打死
            }
        };
    }

    @Override
    void close();
}
