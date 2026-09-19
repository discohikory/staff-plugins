package com.discohikorybrs.authstaff;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * TOTP RFC 6238 (SHA-1, 30s, 6 dígitos) + Base32.
 * © 2026 Discohikorybrs - Daniel Esteban Vera Fernandez.
 */
public final class TotpUtil {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpUtil() {}

    public static String generateSecret() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return base32Encode(b);
    }

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte by : data) {
            buffer = (buffer << 8) | (by & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        s = s.trim().replace("=", "").toUpperCase();
        int buffer = 0, bits = 0;
        byte[] out = new byte[s.length() * 5 / 8 + 1];
        int pos = 0;
        for (char c : s.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Base32 inválido");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[pos++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        byte[] r = new byte[pos];
        System.arraycopy(out, 0, r, 0, pos);
        return r;
    }

    public static String otpAuthUri(String secret, String account, String issuer) {
        return "otpauth://totp/" + url(issuer) + ":" + url(account)
                + "?secret=" + secret + "&issuer=" + url(issuer) + "&digits=6&period=30";
    }

    private static String url(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    public static String currentCode(String secret) {
        return codeAt(secret, System.currentTimeMillis() / 30000L);
    }

    public static boolean verify(String secret, String code) {
        if (code == null || !code.matches("\\d{6}")) return false;
        long step = System.currentTimeMillis() / 30000L;
        return code.equals(codeAt(secret, step))
                || code.equals(codeAt(secret, step - 1))
                || code.equals(codeAt(secret, step + 1));
    }

    private static String codeAt(String secret, long step) {
        try {
            byte[] key = base32Decode(secret);
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) {
                msg[i] = (byte) (step & 0xFF);
                step >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int o = h[h.length - 1] & 0x0F;
            int n = ((h[o] & 0x7F) << 24) | ((h[o + 1] & 0xFF) << 16)
                    | ((h[o + 2] & 0xFF) << 8) | (h[o + 3] & 0xFF);
            return String.format("%06d", n % 1_000_000);
        } catch (Exception e) {
            return "------";
        }
    }
}
