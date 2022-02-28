package dev.aawadia

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.minio.MinioClient
import io.minio.UploadObjectArgs
import io.minio.credentials.AwsEnvironmentProvider
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import io.vertx.micrometer.MicrometerMetricsOptions
import jetbrains.exodus.env.EnvironmentConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

fun main() {
  val vertx = Vertx.vertx(getVertxOptions())
  val dataStore = Datastore()
  deployKvServer(vertx, dataStore)
  scheduleBackup(vertx, dataStore)
  shutdownHook(dataStore)
}

fun deployKvServer(vertx: Vertx, dataStore: Datastore) {
  val websocketsListenersMap = ConcurrentHashMap<String, ServerWebSocket>()
  vertx.deployVerticle({ KVStoreServer(dataStore, websocketsListenersMap) }, getDeploymentOptions())
    .onFailure { it.printStackTrace() }
    .onSuccess { println("deployed kv store") }
}

fun getDeploymentOptions(port: Int = 9090): DeploymentOptions {
  val cpu = Runtime.getRuntime().availableProcessors()
  return DeploymentOptions()
    .setInstances(2 * cpu)
    .setWorkerPoolSize(32 * Runtime.getRuntime().availableProcessors())
    .setConfig(
      JsonObject()
        .put("port", System.getenv("port")?.toIntOrNull() ?: port)
        .put("enable_auth", System.getenv("enable_auth")?.toBoolean() ?: false)
        .put("api_key", System.getenv("api_key") ?: "")
    )
}

fun scheduleBackup(vertx: Vertx, dataStore: Datastore, s3Endpoint: String = "", timer: Long = 86400 * 1000) {
  fun backup() {
    val file = dataStore.takeBackup()
    if (s3Endpoint.isNotEmpty()) {
      runCatching { uploadBackup(file, s3Endpoint) }
        .onSuccess { file.delete() }
        .onFailure { it.printStackTrace() }
    }
  }

  fun doBlockingTask(f: () -> Unit) {
    vertx.executeBlocking<Nothing> { f.invoke() }.onFailure { it.printStackTrace() }
  }

  vertx.setPeriodic(System.getenv("backup_delay")?.toLongOrNull() ?: timer) { doBlockingTask(::backup) }
}

fun uploadBackup(file: File, s3Endpoint: String, bucket: String = "kvdb-backups") {
  val s3Client = MinioClient.builder()
    .endpoint(s3Endpoint)
    .credentialsProvider(AwsEnvironmentProvider())
    .build()

  s3Client.uploadObject(
    UploadObjectArgs.builder()
      .bucket(bucket)
      .`object`(file.name)
      .filename(file.absolutePath)
      .build()
  )
}

fun getEnvConfig(): EnvironmentConfig {
  val config = EnvironmentConfig()
  config.envGatherStatistics = true
  config.envStoreGetCacheSize = 100000
  config.cipherId = "jetbrains.exodus.crypto.streamciphers.JBChaChaStreamCipherProvider"
  config.setCipherKey(System.getenv("cipher_key") ?: "3fc571f15703a04d767a951946eee0877f75f80ebb6f59e5909636e8d9d9d305")
  config.cipherBasicIV = System.getenv("cipher_iv")?.toLongOrNull() ?: 548229163449799110L
  return config
}

fun shutdownHook(dataStore: Datastore) {
  Runtime.getRuntime().addShutdownHook(thread(start = false) { dataStore.close() })
}

fun getPrometheusRegistry(): PrometheusMeterRegistry {
  val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  ClassLoaderMetrics().bindTo(registry)
  JvmMemoryMetrics().bindTo(registry)
  JvmGcMetrics().bindTo(registry)
  ProcessorMetrics().bindTo(registry)
  JvmThreadMetrics().bindTo(registry)
  UptimeMetrics().bindTo(registry)
  FileDescriptorMetrics().bindTo(registry)

  return registry
}

fun getVertxOptions(): VertxOptions {
  return VertxOptions().setMetricsOptions(
    MicrometerMetricsOptions()
      .setEnabled(true)
      .setMicrometerRegistry(getPrometheusRegistry())
  )
}