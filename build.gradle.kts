import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "dev.esplugin"
version = "0.6.1"

val idePath = providers.gradleProperty("idePath")
    .getOrElse("C:/Users/rutkovskiy_ayu/AppData/Local/Programs/IntelliJ IDEA")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")

    intellijPlatform {
        // Local installation of IntelliJ IDEA 2026.2.3 (build 262).
        // Override with -PidePath=... or in gradle.properties.
        local(idePath)
    }
}

tasks.test {
    useJUnitPlatform()
    System.getProperty("es.url")?.let { systemProperty("es.url", it) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "263.*"
        }
    }
}
