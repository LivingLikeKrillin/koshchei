package koshchei.api

import com.zaxxer.hikari.HikariDataSource
import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import koshchei.runtime.DataConverterSupport
import koshchei.runtime.EpisodeReader
import koshchei.runtime.EpisodeStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The episode control plane's beans. Lazy: the context starts without Temporal or the database. */
@Configuration
class EpisodeConfig {
    /** Closed with the context: its Update threads stop, and the stubs go if the client was ever made. */
    @Bean(destroyMethod = "close") fun episodeGateway(): EpisodeApi =
        EpisodeApi(lazy { WorkflowClient.newInstance(WorkflowServiceStubs.newLocalServiceStubs(), DataConverterSupport.clientOptions()) })

    /** Read only (R14): the episode worker creates and writes the tables (design §13). */
    @Bean fun episodeReader(ds: HikariDataSource): EpisodeReader = EpisodeStore { ds.connection }
}
