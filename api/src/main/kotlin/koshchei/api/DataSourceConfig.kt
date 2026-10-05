package koshchei.api

import com.zaxxer.hikari.HikariDataSource
import koshchei.runtime.Db
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DataSourceConfig {
    // Lazy: no connection at startup (minimumIdle 0, no startup probe), so the context starts without the DB and a
    // request that needs it gets the controller's 503.
    @Bean(destroyMethod = "close")
    fun dataSource(): HikariDataSource = HikariDataSource().apply {
        jdbcUrl = Db.url
        username = Db.user
        password = Db.pass
        poolName = "koshchei-api"
        maximumPoolSize = 5
        minimumIdle = 0
        initializationFailTimeout = -1
    }
}
