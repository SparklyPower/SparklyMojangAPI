package net.sparklypower.sparklymojangapi.utils.exposed

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.postgresql.util.PGobject

// From ExposedPowerUtils but updated to work with Exposed 0.50.1
class JsonBinary : ColumnType<String>() {
    override fun sqlType() = "JSONB"

    override fun valueFromDB(value: Any): String {
        return when {
            value is PGobject -> value.value!!
            value is String -> value
            else -> error("Unexpected value $value of type ${value::class.qualifiedName}")
        }
    }

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = value as String?
        stmt.set(index, obj, this)
    }
}

fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonBinary())