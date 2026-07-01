// Root build - wspolna konfiguracja dla wszystkich modulow.
// Zalozenia: Java 21, Paper 1.21.11, Velocity. Pakiet bazowy: gg.elcartel
allprojects {
    group = "gg.elcartel"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        // Paper + Velocity API (oraz ich SNAPSHOTy)
        maven("https://repo.papermc.io/repository/maven-public/")
        // PlaceholderAPI
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
        // VaultAPI (JitPack)
        maven("https://jitpack.io")
    }
}
