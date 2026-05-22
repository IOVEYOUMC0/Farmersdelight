plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.7"
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.7.0")
    }
}

group = "com.huidu.farmersdelight"
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

val obfuscateBuild = providers.gradleProperty("obfuscate")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val debugToolsBuild = providers.gradleProperty("debugTools")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

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

sourceSets {
    named("main") {
        java.srcDir(layout.buildDirectory.dir("generated/sources/buildFlags"))
        if (debugToolsBuild.get()) {
            java.srcDir("src/debugTools/java")
        }
    }
}

val writeBuildFlags = tasks.register("writeBuildFlags") {
    val outputDir = layout.buildDirectory.dir("generated/sources/buildFlags/com/huidu/farmersdelight")
    inputs.property("debugTools", debugToolsBuild)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("BuildFlags.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.huidu.farmersdelight;

            public final class BuildFlags {

                public static final boolean DEBUG_TOOLS = ${debugToolsBuild.get()};

                private BuildFlags() {
                }
            }
            """.trimIndent(),
            Charsets.UTF_8
        )
    }
}

tasks.compileJava {
    dependsOn(writeBuildFlags)
    doFirst {
        if (!debugToolsBuild.get()) {
            delete(layout.buildDirectory.dir("classes/java/main/com/huidu/farmersdelight/debug"))
        }
    }
}

tasks.shadowJar {
    archiveBaseName.set("farmersdelight")
    archiveClassifier.set("")
    if (!debugToolsBuild.get()) {
        exclude("com/huidu/farmersdelight/debug/**")
    }
}

tasks.register<proguard.gradle.ProGuardTask>("obfuscateJar") {
    dependsOn(tasks.shadowJar)
    onlyIf { obfuscateBuild.get() }

    val inputJar = tasks.shadowJar.flatMap { it.archiveFile }
    val outputJar = layout.buildDirectory.file("libs/farmersdelight-${project.version}-obf.jar")

    injars(inputJar)
    outjars(outputJar)

    libraryjars("${System.getProperty("java.home")}/jmods/java.base.jmod")
    libraryjars("${System.getProperty("java.home")}/jmods/java.logging.jmod")
    libraryjars("${System.getProperty("java.home")}/jmods/java.desktop.jmod")
    libraryjars(configurations.compileClasspath.get().files)

    keep("public class com.huidu.farmersdelight.FarmersDelightPlugin { public *; protected *; }")
    keep("class ** extends org.bukkit.plugin.java.JavaPlugin { *; }")
    keepattributes("*Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations")

    dontoptimize()
    dontshrink()
    dontwarn()
    allowaccessmodification()
    overloadaggressively()
    repackageclasses("fd")
    adaptclassstrings()
}

tasks.build {
    dependsOn(tasks.shadowJar)
    if (obfuscateBuild.get()) {
        dependsOn("obfuscateJar")
    }
}
