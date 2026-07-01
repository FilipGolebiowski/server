plugins {
    java
    id("com.gradleup.shadow") version "9.4.2"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    implementation(project(":core-common"))
    implementation(project(":core-data"))
}

tasks {
    shadowJar {
        archiveBaseName.set("core-velocity")
        archiveClassifier.set("")
        // Relokacja, by bundlowane Netty/Reactor (z Lettuce) nie kolidowaly z Netty Velocity.
        relocate("io.netty", "gg.elcartel.lib.netty")
        relocate("reactor", "gg.elcartel.lib.reactor")
    }
    build {
        dependsOn(shadowJar)
    }
}
