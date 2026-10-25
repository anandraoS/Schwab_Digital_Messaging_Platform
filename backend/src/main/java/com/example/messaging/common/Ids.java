package com.example.messaging.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Id generation and hashing helpers. */
public final class Ids {

    private Ids() {}

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Hash used for the suppression list; addresses are normalised (trim + lower case) first. */
    public static String addressHash(String address) {
        return sha256Hex(address == null ? "" : address.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
