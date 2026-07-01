plugins {
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// Lekki plugin trybu: NIC nie bundluje. Typy rdzenia bierze z core-paper (depend w plugin.yml).
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly(project(":core-common"))
    compileOnly(project(":core-data"))
    compileOnly(project(":core-paper"))
}

tasks.compileJava {
    dependsOn(":core-paper:shadowJar")
}
