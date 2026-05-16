plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.7"
}

group = "fr.ateastudio.farmersdelight"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenLocal()
    maven("https://repo.momirealms.net/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")
    compileOnly(files("../Reference/craft-engine-main/core/build/libs/craft-engine-core-26.5.jar"))
    compileOnly(files("../Reference/craft-engine-main/bukkit/build/libs/craft-engine-bukkit-26.5.jar"))
    compileOnly(files("../Reference/craft-engine-main/bukkit/proxy/build/libs/proxy.jar"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
    filesMatching("paper-plugin.yml") {
        expand("version" to version)
    }
}

tasks.shadowJar {
    archiveBaseName.set("farmersdelight")
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
