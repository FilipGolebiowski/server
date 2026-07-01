package gg.elcartel.data.model;

import org.bson.codecs.pojo.annotations.BsonId;

import java.util.UUID;

/**
 * Dane uwierzytelniania (kolekcja "auth"). Oddzielone od profilu - wrazliwe.
 * NIGDY nie zawiera hasla jawnie: tylko sol + hash Argon2id (+ parametry).
 * Sekret TOTP trzymany WYLACZNIE zaszyfrowany (AES-GCM).
 */
public final class AuthRecord {

    @BsonId
    private UUID id;

    // Hasz hasla (Argon2id) - parametry zapisane, by mozna bylo bezpiecznie podbic koszt przy logowaniu.
    private byte[] pwSalt;
    private byte[] pwHash;
    private int pwMemoryKiB;
    private int pwIterations;
    private int pwParallelism;

    // 2FA: sekret TOTP zaszyfrowany (base64 AES-GCM). null => 2FA wylaczone.
    private String totpSecretEnc;
    private boolean totpEnabled;

    // IP-binding / audyt (minimalizacja danych).
    private String registeredIp;
    private String lastLoginIp;

    private int failedAttempts;
    private long lockoutUntil; // epoch ms; 0 = brak blokady
    private long createdAt;
    private long lastLoginAt;

    public AuthRecord() {
    }

    public AuthRecord(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public byte[] getPwSalt() { return pwSalt; }
    public void setPwSalt(byte[] pwSalt) { this.pwSalt = pwSalt; }

    public byte[] getPwHash() { return pwHash; }
    public void setPwHash(byte[] pwHash) { this.pwHash = pwHash; }

    public int getPwMemoryKiB() { return pwMemoryKiB; }
    public void setPwMemoryKiB(int pwMemoryKiB) { this.pwMemoryKiB = pwMemoryKiB; }

    public int getPwIterations() { return pwIterations; }
    public void setPwIterations(int pwIterations) { this.pwIterations = pwIterations; }

    public int getPwParallelism() { return pwParallelism; }
    public void setPwParallelism(int pwParallelism) { this.pwParallelism = pwParallelism; }

    public String getTotpSecretEnc() { return totpSecretEnc; }
    public void setTotpSecretEnc(String totpSecretEnc) { this.totpSecretEnc = totpSecretEnc; }

    public boolean isTotpEnabled() { return totpEnabled; }
    public void setTotpEnabled(boolean totpEnabled) { this.totpEnabled = totpEnabled; }

    public String getRegisteredIp() { return registeredIp; }
    public void setRegisteredIp(String registeredIp) { this.registeredIp = registeredIp; }

    public String getLastLoginIp() { return lastLoginIp; }
    public void setLastLoginIp(String lastLoginIp) { this.lastLoginIp = lastLoginIp; }

    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }

    public long getLockoutUntil() { return lockoutUntil; }
    public void setLockoutUntil(long lockoutUntil) { this.lockoutUntil = lockoutUntil; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(long lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
