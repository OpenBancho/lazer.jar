plugins {
    java
    application
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.osuserverlist"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.osuserverlist.lazer.App")
}

val javalinVersion = "7.2.2"
val jacksonVersion = "2.18.2"
val hikariVersion = "6.2.1"
val mysqlVersion = "9.2.0"
val dotenvVersion = "5.2.2"
val bcprovVersion = "1.80"
val logbackVersion = "1.5.38"
val jedisVersion = "5.2.0"

dependencies {
    implementation("io.javalin:javalin:$javalinVersion")

    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    implementation("com.zaxxer:HikariCP:$hikariVersion")
    implementation("com.mysql:mysql-connector-j:$mysqlVersion")

    implementation("io.github.cdimascio:java-dotenv:$dotenvVersion")

    implementation("org.bouncycastle:bcprov-jdk18on:$bcprovVersion")

    implementation("ch.qos.logback:logback-core:$logbackVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    implementation("redis.clients:jedis:$jedisVersion")
    implementation("io.github.7mochi:osu-native-jar:0.0.9")
    implementation("org.msgpack:msgpack-core:0.9.8")
    implementation("org.msgpack:jackson-dataformat-msgpack:0.9.8")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveFileName.set("lazer-jar-1.0.0-all.jar")
    doLast {
        val src = archiveFile.get().asFile
        val destDir = src.parentFile
        try {
            src.copyTo(File(destDir, "lazer-server-1.0.0-all.jar"), overwrite = true)
        } catch (e: Exception) {
            println("Notice: could not copy to lazer-server-1.0.0-all.jar: ${e.message}")
        }
        try {
            src.copyTo(File(destDir, "lazer.jar"), overwrite = true)
        } catch (e: Exception) {
            println("Notice: could not copy to lazer.jar: ${e.message}")
        }
    }
}
