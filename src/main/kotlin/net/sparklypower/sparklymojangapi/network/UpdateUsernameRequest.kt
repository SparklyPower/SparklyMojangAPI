package net.sparklypower.sparklymojangapi.network

import kotlinx.serialization.Serializable
import net.sparklypower.sparklymojangapi.UUIDAsStringSerializer
import java.util.UUID

@Serializable
data class UpdateUsernameRequest(
    @Serializable(with = UUIDAsStringSerializer::class)
    val id: UUID
)