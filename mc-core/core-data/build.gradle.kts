plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    api(project(":core-common"))

    // Sterowniki bazy. Wersje do weryfikacji przy buildzie (bump, jesli repo poda nowsze).
    api("org.mongodb:mongodb-driver-sync:5.2.1")
    api("io.lettuce:lettuce-core:6.4.0.RELEASE")

    // Krypto: Argon2id (pure-Java, low-level API - bez rejestracji providera JCE).
    api("org.bouncycastle:bcprov-jdk18on:1.84")
}
