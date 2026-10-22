package com.jikestardy.flovvy;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Versioned encrypted device credential only; no chat or user-content database. */
public final class CredentialStore {
    private static final String ALIAS = "flovvy.connection.v1";
    private static final byte[] AAD = "flovvy-credentials-v1".getBytes(StandardCharsets.US_ASCII);
    private final AtomicFile file;

    public CredentialStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "connection.v1"));
    }

    public static final class Credentials {
        public final String origin;
        public final String token;
        public final String deviceId;
        public final HostAccess.Binding binding;
        public Credentials(String origin, String token, String deviceId) {
            this.origin = SessionState.origin(origin);
            if (token == null || !token.matches("[A-Za-z0-9_-]{20,256}")
                    || deviceId == null || !deviceId.matches("[a-f0-9]{24}")) {
                throw new IllegalArgumentException("CREDENTIAL_FORMAT");
            }
            this.token = token;
            this.deviceId = deviceId;
            this.binding = new HostAccess.Binding(this.origin, token, deviceId);
        }
    }

    private SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new GeneralSecurityException("KEY_MISSING");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    public Credentials load() throws GeneralSecurityException, IOException {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return null;
        byte[] encoded;
        try (FileInputStream stream = file.openRead(); ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (result.size() + count > 8192) throw new IOException("CREDENTIAL_SIZE");
                result.write(buffer, 0, count);
            }
            encoded = result.toByteArray();
        }
        if (encoded.length < 29 || encoded[0] != 1) throw new GeneralSecurityException("CREDENTIAL_VERSION");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, encoded, 1, 12));
        cipher.updateAAD(AAD);
        byte[] clear = cipher.doFinal(encoded, 13, encoded.length - 13);
        try {
            JSONObject data = new JSONObject(new String(clear, StandardCharsets.UTF_8));
            if (data.length() != 3) throw new JSONException("CREDENTIAL_FORMAT");
            return new Credentials(data.getString("origin"), data.getString("token"), data.getString("device_id"));
        } catch (JSONException | IllegalArgumentException exception) {
            throw new GeneralSecurityException("CREDENTIAL_FORMAT");
        } finally { java.util.Arrays.fill(clear, (byte) 0); }
    }

    public void save(Credentials credentials) throws GeneralSecurityException, IOException {
        byte[] clear;
        try {
            clear = new JSONObject().put("origin", credentials.origin).put("token", credentials.token)
                    .put("device_id", credentials.deviceId).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException exception) { throw new GeneralSecurityException("CREDENTIAL_FORMAT"); }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(clear);
            byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new GeneralSecurityException("CREDENTIAL_IV");
            FileOutputStream stream = file.startWrite();
            try {
                stream.write(1);
                stream.write(iv);
                stream.write(encrypted);
                file.finishWrite(stream);
            } catch (IOException exception) {
                file.failWrite(stream);
                throw exception;
            }
        } finally { java.util.Arrays.fill(clear, (byte) 0); }
    }

    public void clear() throws GeneralSecurityException, IOException {
        file.delete();
        if (file.getBaseFile().exists()) throw new IOException("CREDENTIAL_DELETE");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
    }
}
