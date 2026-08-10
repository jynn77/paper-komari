plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.papermc.paper"
version = "1.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("net.java.dev.jna:jna:5.14.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    // 不设置 Main-Class（非独立运行，是 Bukkit 插件）
    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // 胖 JAR，包含所有运行时依赖
    val fatJar by registering(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
        archiveBaseName.set("bettermix")
        archiveClassifier.set("")
        archiveVersion.set("")
        from(sourceSets.main.get().output)
        configurations = listOf(project.configurations.runtimeClasspath.get())
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    build {
        dependsOn(fatJar)
    }
}