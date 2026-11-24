package com.privateinvest.crm.common.service;

import com.privateinvest.crm.common.util.AESUtil;
import org.springframework.stereotype.Service;

/**
 * Common Encrypt/Decrypt Service (mirrored from LMS common module).
 */
@Service
public class EncryptDecryptService {

    private final AESUtil aesUtil;

    public EncryptDecryptService(AESUtil aesUtil) {
        this.aesUtil = aesUtil;
    }

    public String encryptResponse(String data) {
        return aesUtil.encrypt(data);
    }

    public String decryptRequest(String encryptedData) {
        return aesUtil.decrypt(encryptedData);
    }
}
