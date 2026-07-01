plugins {
    java
    id("com.gradleup.shadow") version "9.4.2"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    implementation(project(":core-common"))
    implementation(project(":core-data"))
}

tasks {
    shadowJar {
        archiveBaseName.set("core-paper")
        archiveClassifier.set("")
        // Relokacja, by bundlowane Netty/Reactor (z Lettuce) nie kolidowaly z Netty Papera.
        relocate("io.netty", "gg.elcartel.lib.netty")
        relocate("reactor", "gg.elcartel.lib.reactor")
    }
    build {
        dependsOn(shadowJar)
    }
}
