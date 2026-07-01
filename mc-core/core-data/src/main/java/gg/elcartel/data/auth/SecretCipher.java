package gg.elcartel.data.auth;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Szyfrowanie sekretow w spoczynku (AES-256-GCM). Uzywane dla sekretow TOTP.
 * Klucz (32 bajty) pochodzi ze ZMIENNEJ SRODOWISKOWEJ / menedzera sekretow - NIGDY z repo.
 */
public final class SecretCipher {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom rng = new SecureRandom();

    public SecretCipher(byte[] key32) {
        if (key32 == null || key32.length != 32) {
            throw new IllegalArgumentException("Klucz AES musi miec 32 bajty (256-bit).");
        }
        this.key = new SecretKeySpec(key32, "AES");
    }

    public static SecretCipher fromBase64(String base64Key) {
        return new SecretCipher(Base64.getDecoder().decode(base64Key));
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Szyfrowanie nieudane", e);
        }
    }

    public String decrypt(String base64) {
        try {
            byte[] in = Base64.getDecoder().decode(base64);
            byte[] iv = Arrays.copyOfRange(in, 0, IV_LEN);
            byte[] ct = Arrays.copyOfRange(in, IV_LEN, in.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Deszyfrowanie nieudane", e);
        }
    }
}
