import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.20"
    application
}

kotlin {
    // niente jvmToolchain: usa il JDK presente (17 in Docker/CI, 21 altrove)
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    // accesso alla porta seriale (il vLinker in Bluetooth su macOS appare
    // come /dev/tty.*). Unica dipendenza esterna oltre al core.
    implementation("com.fazecast:jSerialComm:2.11.0")
}

application {
    mainClass.set("cafe.megane.desktop.MainKt")
}

// JAR eseguibile con tutte le dipendenze dentro: "java -jar megane-desktop.jar"
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("megane-desktop")
    manifest { attributes["Main-Class"] = "cafe.megane.desktop.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
    with(tasks.jar.get())
}
