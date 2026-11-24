package com.privateinvest.crm.common.util;

import com.privateinvest.crm.common.entity.JavaConfig;
import com.privateinvest.crm.common.repository.JavaConfigRepository;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Common AES Encryption & Decryption Utility (mirrored from LMS common module).
 * Algorithm: AES/CBC/PKCS5Padding with 16-byte SecretKey and 16-byte IV.
 */
@Component
public class AESUtil {

    private static final String ALGO = "AES/CBC/PKCS5Padding";
    private static final String DEFAULT_SECRET_KEY = "PrivateInvestCRMKey16ByteLength!";
    private static final String DEFAULT_IV = "PrivateInvestIv16";

    private final JavaConfigRepository javaConfigRepository;

    public AESUtil(JavaConfigRepository javaConfigRepository) {
        this.javaConfigRepository = javaConfigRepository;
    }

    private SecretKeySpec getSecretKey() {
        String key = javaConfigRepository
                .findByKey("aesSecretKey")
                .map(JavaConfig::getValue)
                .orElse(DEFAULT_SECRET_KEY);
        return new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
    }

    private IvParameterSpec getIv() {
        String iv = javaConfigRepository
                .findByKey("aesIv")
                .map(JavaConfig::getValue)
                .orElse(DEFAULT_IV);
        return new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(String data) {
        if (data == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), getIv());
            byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Payload encryption failed", e);
        }
    }

    public String decrypt(String encryptedData) {
        if (encryptedData == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), getIv());
            byte[] decoded = Base64.getDecoder().decode(encryptedData);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Payload decryption failed", e);
        }
    }
}
