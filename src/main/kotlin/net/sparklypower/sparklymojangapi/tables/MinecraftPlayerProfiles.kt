package net.sparklypower.sparklymojangapi.tables

import net.sparklypower.sparklymojangapi.utils.exposed.jsonb
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

object MinecraftPlayerProfiles : UUIDTable() {
    val data = jsonb("data").nullable()
    val queriedAt = timestampWithTimeZone("queried_at").index()
}