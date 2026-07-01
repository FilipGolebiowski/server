plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// Czysta Java, bez zaleznosci platformowych (latwe testy jednostkowe).
