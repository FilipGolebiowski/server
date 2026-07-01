package gg.elcartel.data.auth;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Haszowanie hasel Argon2id (Bouncy Castle, pure-Java).
 * Bezpieczenstwo: losowa sol per haslo, stale-czasowe porownanie, czyszczenie bajtow hasla,
 * przyjmuje char[] (nie String) - haslo nie zostaje w puli stringow. NIC nie loguje.
 * Domyslny profil: OWASP-mocny m=64 MiB, t=3, p=1 (~250-400 ms). Przy duzym ruchu mozna
 * zejsc do m=19456, t=2, p=1 (rownowazna obrona wg OWASP, mniej RAM/CPU).
 */
public final class PasswordHasher {

    private static final int HASH_LEN = 32;
    private static final int SALT_LEN = 16;

    private final int memoryKiB;
    private final int iterations;
    private final int parallelism;
    private final SecureRandom rng = new SecureRandom();

    public PasswordHasher() {
        this(65536, 3, 1);
    }

    public PasswordHasher(int memoryKiB, int iterations, int parallelism) {
        this.memoryKiB = memoryKiB;
        this.iterations = iterations;
        this.parallelism = parallelism;
    }

    public PasswordHash hash(char[] password) {
        byte[] salt = new byte[SALT_LEN];
        rng.nextBytes(salt);
        byte[] h = compute(password, salt, memoryKiB, iterations, parallelism);
        return new PasswordHash(salt, h, memoryKiB, iterations, parallelism);
    }

    /** Weryfikacja stale-czasowa wg parametrow zapisanych przy rejestracji. */
    public boolean verify(char[] password, byte[] salt, byte[] expected,
                          int memKiB, int iters, int par) {
        byte[] h = compute(password, salt, memKiB, iters, par);
        boolean ok = MessageDigest.isEqual(h, expected);
        Arrays.fill(h, (byte) 0);
        return ok;
    }

    /** true => parametry zapisanego hasha sa slabsze niz biezace (re-hash przy nastepnym logowaniu). */
    public boolean needsRehash(int memKiB, int iters, int par) {
        return memKiB < memoryKiB || iters < iterations || par < parallelism;
    }

    private byte[] compute(char[] password, byte[] salt, int m, int t, int p) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(m)
            .withIterations(t)
            .withParallelism(p)
            .withSalt(salt)
            .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);

        byte[] pwBytes = toUtf8(password);
        byte[] out = new byte[HASH_LEN];
        try {
            generator.generateBytes(pwBytes, out);
        } finally {
            Arrays.fill(pwBytes, (byte) 0);
        }
        return out;
    }

    private static byte[] toUtf8(char[] chars) {
        CharBuffer cb = CharBuffer.wrap(chars);
        ByteBuffer bb = StandardCharsets.UTF_8.encode(cb);
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        return out;
    }
}
