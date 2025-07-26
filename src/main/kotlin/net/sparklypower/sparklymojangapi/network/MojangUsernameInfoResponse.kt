package net.sparklypower.sparklymojangapi.network

import kotlinx.serialization.Serializable
import net.sparklypower.sparklymojangapi.NonDashedUUIDAsStringSerializer
import net.sparklypower.sparklymojangapi.UUIDAsStringSerializer
import java.util.UUID

@Serializable
data class MojangUsernameInfoResponse(
    @Serializable(with = NonDashedUUIDAsStringSerializer::class)
    val id: UUID,
    val name: String,
)