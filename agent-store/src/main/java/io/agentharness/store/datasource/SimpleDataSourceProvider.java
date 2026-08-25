package io.agentharness.store.datasource;

import javax.sql.DataSource;

/** 开发期使用的数据源提供者，自带 {@link SimpleDataSource} 那个小池子。 */
public final class SimpleDataSourceProvider implements DataSourceProvider {

    private final SimpleDataSource dataSource;

    public SimpleDataSourceProvider(DataSourceConfig config) {
        this.dataSource = new SimpleDataSource(config);
    }

    @Override
    public DataSource dataSource() {
        return dataSource;
    }

    public SimpleDataSource pool() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
