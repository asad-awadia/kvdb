package dev.aawadia

import io.micrometer.prometheus.PrometheusMeterRegistry
import io.vertx.core.AbstractVerticle
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.*
import io.vertx.micrometer.backends.BackendRegistries
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class KVStoreServer(
  private val ds: Datastore = Datastore(),
  private val listeners: ConcurrentHashMap<String, ServerWebSocket> = ConcurrentHashMap<String, ServerWebSocket>()
) : AbstractVerticle() {
  private val registry = BackendRegistries.getDefaultNow() as PrometheusMeterRegistry

  private val logger = Logger.getLogger(KVStoreServer::class.java.name)
  override fun start() {
    val router = Router.router(vertx)
    setupMiddleware(router)

    router.route("/ws/v1/${getApiKey()}").handler(this::handleWebsockets)

    router.get("/kv/v1/range").handler(this::handleGetKeyRange)
    router.get("/kv/v1/batch").handler(this::handleBatchGet)
    router.get("/kv/v1/:key").handler(this::handleGetKey)

    router.put("/kv/v1/:key/:value").blockingHandler(this::handlePutKey)
    router.post("/kv/v1/:key").blockingHandler(this::handlePostKey)
    router.delete("/kv/v1/:key").blockingHandler(this::handleDeleteKey)

    router.get("/admin/db/stats").handler(this::getDBStats)
    router.get("/admin/metrics").handler { it.response().end(registry.scrape()) }

    vertx.createHttpServer(getHttpServerOptions())
      .requestHandler(router)
      .exceptionHandler { it.printStackTrace() }
      .listen(config().getInteger("port", 9090))
      .onFailure { logger.warning(it.message) }
      .onSuccess { logger.info("server started on port " + it.actualPort()) }
  }

  private fun handleBatchGet(routingContext: RoutingContext) {
    val response = JsonObject()
    routingContext.queryParam("keys").first()
      .split(",")
      .forEach { response.put(it, ds.get(it)) }

    routingContext.response()
      .putHeader("Content-type", "application/json; charset=utf-8")
      .end(response.encodePrettily())
  }

  private fun handleGetKeyRange(routingContext: RoutingContext) {
    val response = JsonObject()
    ds.getKeyRange(
      routingContext.queryParam("from").firstOrNull() ?: "",
      routingContext.queryParam("to").firstOrNull() ?: ""
    ).forEach {
      response.put(it.key, it.value)
    }
    routingContext.response()
      .putHeader("Content-type", "application/json; charset=utf-8")
      .end(response.encodePrettily())
  }

  private fun handleGetKey(routingContext: RoutingContext) {
    val response = JsonObject()
    val key = routingContext.pathParam("key")
    response.put(key, ds.get(key))
    routingContext.response()
      .putHeader("Content-type", "application/json; charset=utf-8")
      .end(response.encodePrettily())
  }

  private fun handlePutKey(routingContext: RoutingContext) {
    val key = routingContext.pathParam("key")
    val value = routingContext.pathParam("value")
    ds.insert(key, value)
    val response = JsonObject().put(key, value)
    routingContext.response()
      .putHeader("Content-type", "application/json; charset=utf-8")
      .end(response.encodePrettily())

    listeners.forEach { it.value.writeTextMessage(response.put("op", "put").encode()) }
  }

  private fun handlePostKey(routingContext: RoutingContext) {
    val key = routingContext.pathParam("key")
    val value = routingContext.bodyAsJson.getString("value") ?: throw IllegalArgumentException("value is null")
    ds.insert(key, value)
    val response = JsonObject().put(key, value)
    routingContext.response()
      .putHeader("Content-type", "application/json; charset=utf-8")
      .end(response.encodePrettily())

    listeners.forEach { it.value.writeTextMessage(response.put("op", "put").encode()) }
  }

  private fun handleDeleteKey(routingContext: RoutingContext) {
    val key = routingContext.pathParam("key")
    val oldValue = ds.get(key)
    ds.delete(key)
    val response = JsonObject().put(key, oldValue)
    routingContext.response()
      .putHeader("Content-type", "application/json; charset=utf-8")
      .end(response.encodePrettily())

    listeners.forEach { it.value.writeTextMessage(response.put("op", "delete").encode()) }
  }

  private fun handleWebsockets(routingContext: RoutingContext) {
    routingContext.request()
      .toWebSocket()
      .onSuccess { webSocket ->
        listeners[webSocket.textHandlerID()] = webSocket
        webSocket.closeHandler { listeners.remove(webSocket.textHandlerID()) }
      }.onFailure { logger.warning(it.message) }
  }

  private fun getDBStats(routingContext: RoutingContext) {
    val response = JsonObject()
    ds.getDbStats().forEach { response.put(it.key, it.value) }
    response.put("listeners_count", listeners.count())
    routingContext.response().end(response.encodePrettily())
  }

  private fun handleFailure(routingContext: RoutingContext) {
    routingContext.response()
      .putHeader("Content-type", "application/json; charset=utf-8")
      .setStatusCode(500)
      .end(routingContext.failure().message ?: "internal server error")
  }

  private fun setupMiddleware(router: Router) {
    router.route().handler(BodyHandler.create())
    router.route("/kv*").handler(TimeoutHandler.create())
    router.route().handler(ResponseTimeHandler.create())
    router.route().handler(LoggerHandler.create(LoggerFormat.SHORT))
    router.route().failureHandler(this::handleFailure)
    if (authEnabled()) {
      authMiddleware(router)
    }
  }

  private fun authMiddleware(router: Router) {
    router.route("/kv*").handler(this::bearerAuth)
    router.route("/admin*").handler(this::bearerAuth)
  }

  private fun bearerAuth(routingContext: RoutingContext) {
    if (!routingContext.request().headers().contains("Authorization")) {
      routingContext.fail(403, IllegalAccessException())
      return
    }
    val authHeader = routingContext.request().getHeader("Authorization").ifEmpty { "Bearer " }
    val split = authHeader.split("Bearer ")
    if (split.size != 2 || split[1] != getApiKey()) {
      routingContext.fail(403, IllegalAccessException())
      return
    }
    routingContext.next()
  }

  private fun getApiKey(): String {
    return if (authEnabled()) config().getString("api_key", "admin").ifEmpty { "admin" }
    else ""
  }

  private fun authEnabled(): Boolean {
    return config().getBoolean("enable_auth", false)
  }

  private fun getHttpServerOptions(): HttpServerOptions {
    val serverOptions = HttpServerOptions()
    serverOptions.compressionLevel = 3
    serverOptions.isCompressionSupported = true
    serverOptions.acceptBacklog = 1024
    serverOptions.isTcpFastOpen = true
    serverOptions.isTcpNoDelay = true
    serverOptions.isTcpKeepAlive = true
    return serverOptions
  }
}
