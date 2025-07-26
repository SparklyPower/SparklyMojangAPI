package net.perfectdreams.custard.tables

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable

object SchemaVersion : UUIDTable() {
    val version = integer("version")
}