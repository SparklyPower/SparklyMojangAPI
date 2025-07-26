package net.sparklypower.sparklymojangapi.config

import kotlinx.serialization.Serializable

@Serializable
data class SparklyMojangAPIConfig(
    val database: DatabaseConfig
) {
    @Serializable
    data class DatabaseConfig(
        val database: String,
        val address: String,
        val username: String,
        val password: String
    )
}