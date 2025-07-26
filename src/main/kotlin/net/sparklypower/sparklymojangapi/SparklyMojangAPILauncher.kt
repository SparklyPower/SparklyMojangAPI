package net.sparklypower.sparklymojangapi

import com.typesafe.config.ConfigFactory
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import net.perfectdreams.harmony.logging.HarmonyLoggerFactory
import net.perfectdreams.harmony.logging.slf4j.HarmonyLoggerCreatorSLF4J
import net.sparklypower.sparklymojangapi.config.SparklyMojangAPIConfig
import java.io.File

object SparklyMojangAPILauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        HarmonyLoggerFactory.setLoggerCreator(HarmonyLoggerCreatorSLF4J())

        val config = Hocon.decodeFromConfig<SparklyMojangAPIConfig>(ConfigFactory.parseFile(File("sparklymojangapi.conf")))

        val m = SparklyMojangAPI(config)
        m.start()
    }
}