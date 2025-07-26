package net.sparklypower.sparklymojangapi

import net.perfectdreams.harmony.logging.HarmonyLoggerFactory
import net.perfectdreams.harmony.logging.slf4j.HarmonyLoggerCreatorSLF4J

object SparklyMojangAPILauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        HarmonyLoggerFactory.setLoggerCreator(HarmonyLoggerCreatorSLF4J())
        val m = SparklyMojangAPI()
        m.start()
    }
}