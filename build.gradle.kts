plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("com.google.cloud.tools.jib") version "3.4.5"
}

group = "net.sparklypower.sparklymojangapi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.perfectdreams.net/")
}

dependencies {
    implementation("io.ktor:ktor-server-cio:3.2.2")
    implementation("io.ktor:ktor-client-cio:3.2.2")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.7")

    implementation("net.perfectdreams.harmony.logging:harmonylogging-slf4j:1.0.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    implementation("org.jetbrains.exposed:exposed-java-time:1.0.0-beta-4")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.0.0-beta-4")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-hocon:1.9.0")

    implementation("com.github.luben:zstd-jni:1.5.7-4")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

jib {
    container {
        ports = listOf("9999")
        mainClass = "net.sparklypower.sparklymojangapi.SparklyMojangAPILauncher"
    }

    to {
        image = "ghcr.io/sparklypower/sparklymojangapi"

        auth {
            username = System.getProperty("DOCKER_USERNAME") ?: System.getenv("DOCKER_USERNAME")
            password = System.getProperty("DOCKER_PASSWORD") ?: System.getenv("DOCKER_PASSWORD")
        }
    }

    from {
        image = "eclipse-temurin:24.0.1_9-jdk-noble"
    }
}

kotlin {
    jvmToolchain(21)
}