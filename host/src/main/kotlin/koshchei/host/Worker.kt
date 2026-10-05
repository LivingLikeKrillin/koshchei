package koshchei.host

import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.worker.WorkerFactory
import koshchei.runtime.DataConverterSupport
import koshchei.runtime.EPISODE_TASK_QUEUE
import koshchei.runtime.EpisodeRuntimeConfig
import koshchei.runtime.EpisodeStore
import koshchei.runtime.EpisodeWorkers
import koshchei.runtime.PicassoMode
import java.nio.file.Files
import kotlin.system.exitProcess

/** The episode worker's configuration; `off` is refused because the episode worker is all koshchei runs. */
fun workerConfig(env: Map<String, String>): EpisodeRuntimeConfig {
    val config = EpisodeRuntimeConfig.fromEnv(env)
    check(config.picasso != PicassoMode.OFF) {
        "KOSHCHEI_PICASSO is unset or off: name the approval window, mock (the test's) or picasso (the real one)"
    }
    return config
}

fun main() {
    val workerName = System.getenv("KOSHCHEI_WORKER_NAME") ?: "worker-1"
    val config = try { workerConfig(System.getenv()) } catch (e: IllegalStateException) {
        System.err.println("[$workerName] ERROR ${e.message}")
        exitProcess(1)
    }

    val service = WorkflowServiceStubs.newLocalServiceStubs() // localhost:7233
    val client = WorkflowClient.newInstance(service, DataConverterSupport.clientOptions())
    val factory = WorkerFactory.newInstance(client)

    val login = EpisodeDbLogin.fromEnv(System.getenv())
    println("[$workerName] episode tables as DB user '${login.user}' (KOSHCHEI_EPISODE_DB_USER, else KOSHCHEI_DB_USER)")
    val store = EpisodeStore { login.connect() }
    store.ensureSchema()
    EpisodeWorkers.register(factory, config, store)
    if (config.picasso == PicassoMode.MOCK) {
        System.err.println("[$workerName] WARNING episode worker on $EPISODE_TASK_QUEUE with the TEST Mock picasso: remedies reach no robot (design §8.3, §11)")
    }
    if (config.picasso == PicassoMode.PICASSO) {
        // The URL only: approver ids stay out of logs (EpisodeActivitiesImpl), the configured agent id included.
        println("[$workerName] episode approvals go to ${config.window!!.url} as PERSON, or AGENT under the configured agent id; revalidation is UNKNOWN, so a person confirms each precondition")
    }
    // The default path is relative to the working directory. A missing file is read as MISSING on every decision, so
    // every episode escalates POLICY_MISSING (design §8.2): say so once here rather than leave it to the first episode.
    if (!Files.exists(config.policyPath)) {
        System.err.println("[$workerName] WARNING episode policy ${config.policyPath.toAbsolutePath()} does not exist: every episode will escalate POLICY_MISSING (set KOSHCHEI_EPISODE_POLICY)")
    }
    println("[$workerName] episode worker on $EPISODE_TASK_QUEUE (narrator ${config.narrator}, policy ${config.policyPath})")
    factory.start()
}
