package gg.elcartel.data.auth;

/** Wynik haszowania hasla: sol + hash + parametry Argon2id (do zapisania w AuthRecord). */
public final class PasswordHash {

    private final byte[] salt;
    private final byte[] hash;
    private final int memoryKiB;
    private final int iterations;
    private final int parallelism;

    public PasswordHash(byte[] salt, byte[] hash, int memoryKiB, int iterations, int parallelism) {
        this.salt = salt;
        this.hash = hash;
        this.memoryKiB = memoryKiB;
        this.iterations = iterations;
        this.parallelism = parallelism;
    }

    public byte[] salt() { return salt; }
    public byte[] hash() { return hash; }
    public int memoryKiB() { return memoryKiB; }
    public int iterations() { return iterations; }
    public int parallelism() { return parallelism; }
}
