package net.sparklypower.sparklymojangapi.tables

import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object MinecraftPlayerNames : IdTable<String>() {
    override val id = varchar("username", 16).uniqueIndex().index().entityId()
    val uniqueId = uuid("unique_id").nullable()
    val queriedAt = timestampWithTimeZone("queried_at").index()
}