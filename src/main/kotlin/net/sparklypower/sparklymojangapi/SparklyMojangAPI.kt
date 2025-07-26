package net.sparklypower.sparklymojangapi

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import net.perfectdreams.custard.Custard
import net.perfectdreams.harmony.logging.HarmonyLoggerFactory
import net.sparklypower.sparklymojangapi.config.SparklyMojangAPIConfig
import net.sparklypower.sparklymojangapi.network.MojangUsernameInfoResponse
import net.sparklypower.sparklymojangapi.network.UpdateUsernameRequest
import net.sparklypower.sparklymojangapi.network.UsernameInfoResponse
import net.sparklypower.sparklymojangapi.tables.MinecraftPlayerNames
import net.sparklypower.sparklymojangapi.tables.MinecraftPlayerProfiles
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.*

class SparklyMojangAPI(val config: SparklyMojangAPIConfig) {
    companion object {
        private val logger by HarmonyLoggerFactory.logger {}
        private val ZONE_ID_UTC = ZoneId.of("UTC")

        fun convertNonDashedToUniqueID(id: String): UUID {
            return UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20, 32))
        }
    }

    val http = HttpClient {}

    val pendingUsernameQueries = Channel<PendingUsername>(Channel.UNLIMITED)
    val keyedMutex = KeyedMutex<UUID>()

    fun start() {
        val custard = Custard.createPostgreSQLPudding(
            config.database.address,
            config.database.database,
            config.database.username,
            config.database.password
        )

        runBlocking {
            custard.createMissingTablesAndColumns { true }
        }

        // PENDING USERNAME BATCH PROCESSOR
        GlobalScope.launch {
            while (true) {
                val thisBatch = mutableListOf<PendingUsername>()

                val expiredTTL = OffsetDateTime.now(ZONE_ID_UTC).minusMinutes(5)

                val firstRequest = pendingUsernameQueries.receive() // This will suspend (which is what we want to avoid running this on an infinite loop!)
                var nextRequest = firstRequest

                while (thisBatch.map { it.username }.distinct().size != 10) {
                    // Before we add this to the batch... Is this already cached?
                    val playerNameResult = custard.transaction {
                        MinecraftPlayerNames.selectAll()
                            .where {
                                MinecraftPlayerNames.id eq nextRequest.username and (MinecraftPlayerNames.queriedAt greaterEq expiredTTL)
                            }
                            .firstOrNull()
                    }

                    if (playerNameResult != null) {
                        nextRequest.deferred.complete(
                            PendingUsername.PendingUsernameResult(
                                playerNameResult[MinecraftPlayerNames.uniqueId],
                                playerNameResult[MinecraftPlayerNames.queriedAt]
                            )
                        )
                    } else {
                        // Needs to be queried!
                        thisBatch.add(nextRequest)
                    }

                    nextRequest = pendingUsernameQueries.tryReceive().getOrNull() ?: break
                }

                // No values in the batch, that means that all queries were already cached!
                // Bailing out...
                if (thisBatch.isEmpty())
                    continue

                logger.info { "Batching requesting ${thisBatch.map { it.username }} usernames..." }

                try {
                    logger.info { "Processing batch..." }

                    val payload = http.post("https://api.mojang.com/profiles/minecraft") {
                        setBody(
                            TextContent(
                                buildJsonArray {
                                    for (username in thisBatch) {
                                        add(username.username)
                                    }
                                }.toString(),
                                ContentType.Application.Json
                            )
                        )
                    }

                    if (payload.status == HttpStatusCode.TooManyRequests) {
                        // If we are rate-limited, we are going to wait 1s and try again.
                        logger.warn { "We are being rate limited by Mojang API, waiting 1s and trying again..." }
                        for (request in thisBatch) {
                            // Readd the pending requests to the pending channel
                            pendingUsernameQueries.send(request)
                        }
                        delay(1_000)
                        continue
                    }

                    val batchedResult = Json.decodeFromString<List<MojangUsernameInfoResponse>>(payload.bodyAsText())

                    for (request in thisBatch) {
                        val result = batchedResult.find { it.name == request.username }

                        if (result != null) {
                            val uniqueId = result.id
                            custard.transaction {
                                MinecraftPlayerNames.upsert(MinecraftPlayerNames.id) {
                                    it[MinecraftPlayerNames.id] = result.name
                                    it[MinecraftPlayerNames.uniqueId] = uniqueId
                                    it[MinecraftPlayerNames.queriedAt] = OffsetDateTime.now(ZONE_ID_UTC)
                                }
                            }

                            request.deferred.complete(
                                PendingUsername.PendingUsernameResult(
                                    uniqueId,
                                    null
                                )
                            )
                        } else {
                            custard.transaction {
                                MinecraftPlayerNames.upsert(MinecraftPlayerNames.id) {
                                    it[MinecraftPlayerNames.id] = request.username
                                    it[MinecraftPlayerNames.uniqueId] = null
                                    it[MinecraftPlayerNames.queriedAt] = OffsetDateTime.now(ZONE_ID_UTC)
                                }
                            }

                            request.deferred.complete(
                                PendingUsername.PendingUsernameResult(
                                    null,
                                    null
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Something went wrong while trying to query for usernames! Failing all requests in this batch..." }
                    thisBatch.forEach { it.deferred.completeExceptionally(e) }
                }
            }
        }

        val http = embeddedServer(CIO, port = 9999) {
            routing {
                // Username to UUID
                // Mojang ratelimits these 600 requests per 10 minutes
                get("/users/profiles/minecraft/{username}") {
                    val username = call.parameters["username"] ?: return@get

                    logger.info { "Username to UUID requested for Username $username" }

                    // TODO: It is better to check if it is already cached before sending it to the batcher
                    val deferred = CompletableDeferred<PendingUsername.PendingUsernameResult>()
                    pendingUsernameQueries.send(PendingUsername(username, deferred))

                    val result = deferred.await()

                    if (result.cachedDate != null) {
                        logger.info { "Username to UUID requested for Username $username - Loaded from cache!" }

                        call.response.header("SparklyMojangAPI-Cached", "true")
                        call.response.header("SparklyMojangAPI-Cached-Time", result.cachedDate.toString())
                    } else {
                        logger.info { "Username to UUID requested for Username $username - Loaded from Mojang API!" }

                        call.response.header("SparklyMojangAPI-Cached", "false")
                    }

                    if (result.uniqueId == null) {
                        call.respondText("", status = HttpStatusCode.NotFound)
                        return@get
                    }

                    call.respondText(
                        Json.encodeToString(
                            UsernameInfoResponse(
                                result.uniqueId,
                                username
                            )
                        ),
                        ContentType.Application.Json
                    )
                }

                // UUID to Profile and Skin/Cape
                // Mojang ratelimits these 200 requests per 1 minute
                get("/session/minecraft/profile/{uniqueId}") {
                    val uniqueId = UUID.fromString(call.parameters["uniqueId"]) ?: return@get
                    val expiredTTL = OffsetDateTime.now(ZONE_ID_UTC).minusMinutes(5)

                    logger.info { "UUID to Profile and Skin/Cape requested for UUID $uniqueId" }

                    // We used keyed mutexes to make sure that only one request for a specific UUID is being processed at a time
                    keyedMutex.withLock(uniqueId) {
                        // Pending profiles are a bit *different* than usernames because we can't batch UUIDs
                        val cachedResult = custard.transaction {
                            MinecraftPlayerProfiles.selectAll()
                                .where {
                                    MinecraftPlayerProfiles.id eq uniqueId and (MinecraftPlayerProfiles.queriedAt greaterEq expiredTTL)
                                }
                                .firstOrNull()
                        }

                        if (cachedResult != null) {
                            logger.info { "UUID to Profile and Skin/Cape requested for UUID $uniqueId - Loaded from cache!" }
                            call.response.header("SparklyMojangAPI-Cached", "true")
                            call.response.header("SparklyMojangAPI-Cached-Time", cachedResult[MinecraftPlayerProfiles.queriedAt].toString())
                            call.respondText(
                                cachedResult[MinecraftPlayerProfiles.data] ?: "",
                                status = if (cachedResult[MinecraftPlayerProfiles.data] != null)
                                    HttpStatusCode.OK
                                else
                                    HttpStatusCode.NotFound,
                                contentType = ContentType.Application.Json
                            )
                            return@withLock
                        }

                        val data: String?

                        while (true) {
                            val payload = http.get("https://sessionserver.mojang.com/session/minecraft/profile/$uniqueId?unsigned=false")

                            if (payload.status == HttpStatusCode.TooManyRequests) {
                                // If we are rate-limited, we are going to wait 1s and try again.
                                logger.warn { "We are being rate limited by Mojang API, waiting 1s and trying again..." }
                                delay(1_000)
                                continue
                            }

                            // Technically it is ALWAYS "no content", HOWEVER to be consistent with the other user API we will always return Not Found if it wasn't found
                            if (payload.status == HttpStatusCode.NotFound || payload.status == HttpStatusCode.NoContent) {
                                data = null
                                break
                            }

                            data = payload.bodyAsText()
                            break
                        }

                        logger.info { "UUID to Profile and Skin/Cape requested for UUID $uniqueId - Loaded from Mojang API!" }

                        custard.transaction {
                            MinecraftPlayerProfiles.upsert(MinecraftPlayerProfiles.id) {
                                it[MinecraftPlayerProfiles.id] = uniqueId
                                it[MinecraftPlayerProfiles.data] = data
                                it[MinecraftPlayerProfiles.queriedAt] = OffsetDateTime.now(ZONE_ID_UTC)
                            }
                        }

                        call.response.header("SparklyMojangAPI-Cached", "false")
                        call.respondText(
                            data ?: "",
                            status = if (data != null)
                                HttpStatusCode.OK
                            else
                                HttpStatusCode.NotFound,
                            contentType = ContentType.Application.Json
                        )
                    }
                }

                post("/update/data/{username}") {
                    val request = Json.decodeFromString<UpdateUsernameRequest>(call.receiveText())

                    custard.transaction {
                        MinecraftPlayerNames.upsert(MinecraftPlayerNames.id) {
                            it[MinecraftPlayerNames.id] = call.parameters["username"]!!
                            it[MinecraftPlayerNames.uniqueId] = request.id
                            it[MinecraftPlayerNames.queriedAt] = OffsetDateTime.now(ZONE_ID_UTC)
                        }
                    }

                    call.respondText("", status = HttpStatusCode.NoContent)
                }
            }
        }

        http.start(wait = true)
    }

    data class PendingUsername(
        val username: String,
        val deferred: CompletableDeferred<PendingUsernameResult>
    ) {
        data class PendingUsernameResult(
            val uniqueId: UUID?,
            val cachedDate: OffsetDateTime?
        )
    }

    data class PendingProfile(
        val uniqueId: UUID,
        val deferred: CompletableDeferred<PendingProfileResult>
    ) {
        data class PendingProfileResult(
            val data: String?,
            val cachedDate: OffsetDateTime?
        )
    }
}