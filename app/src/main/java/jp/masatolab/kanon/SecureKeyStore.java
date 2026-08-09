package jp.masatolab.kanon;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureKeyStore {
    private static final String PREFS = "kanon_secure";
    private static final String KEY_ALIAS = "kanon_byok_aes";
    private static final String PREF_CIPHERTEXT = "api_key_ct";
    private static final String PREF_IV = "api_key_iv";

    private SecureKeyStore() {}

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return gen.generateKey();
    }

    public static void save(Context context, String apiKey) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            clear(context);
            return;
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ct = cipher.doFinal(apiKey.trim().getBytes(StandardCharsets.UTF_8));
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_CIPHERTEXT, Base64.encodeToString(ct, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    public static String load(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String ct64 = prefs.getString(PREF_CIPHERTEXT, "");
            String iv64 = prefs.getString(PREF_IV, "");
            if (ct64.isEmpty() || iv64.isEmpty()) return "";
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Base64.decode(iv64, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(ct64, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean hasKey(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !prefs.getString(PREF_CIPHERTEXT, "").isEmpty()
                && !prefs.getString(PREF_IV, "").isEmpty();
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
