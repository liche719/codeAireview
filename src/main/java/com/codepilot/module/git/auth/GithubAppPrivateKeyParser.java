package com.codepilot.module.git.auth;

import com.codepilot.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

class GithubAppPrivateKeyParser {

    PrivateKey parse(String appPrivateKey, String appPrivateKeyBase64) throws Exception {
        String key = appPrivateKey;
        byte[] rawDer = null;
        if (!StringUtils.hasText(key) && StringUtils.hasText(appPrivateKeyBase64)) {
            byte[] decoded = Base64.getDecoder().decode(appPrivateKeyBase64.trim());
            String decodedText = new String(decoded, StandardCharsets.UTF_8);
            if (decodedText.contains("BEGIN")) {
                key = decodedText;
            } else {
                rawDer = decoded;
            }
        }
        if (rawDer != null) {
            return generatePrivateKey(rawDer);
        }
        if (!StringUtils.hasText(key)) {
            throw new BusinessException("GitHub App private key is missing");
        }
        String normalized = key.replace("\\n", "\n").trim();
        if (normalized.contains("BEGIN RSA PRIVATE KEY")) {
            byte[] pkcs1 = decodePem(normalized, "RSA PRIVATE KEY");
            return generatePrivateKey(wrapPkcs1RsaPrivateKey(pkcs1));
        }
        if (normalized.contains("BEGIN PRIVATE KEY")) {
            return generatePrivateKey(decodePem(normalized, "PRIVATE KEY"));
        }
        return generatePrivateKey(Base64.getDecoder().decode(normalized));
    }

    private PrivateKey generatePrivateKey(byte[] der) throws Exception {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception exception) {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(wrapPkcs1RsaPrivateKey(der)));
        }
    }

    private byte[] decodePem(String pem, String type) {
        String content = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(content);
    }

    private byte[] wrapPkcs1RsaPrivateKey(byte[] pkcs1) {
        byte[] version = new byte[]{0x02, 0x01, 0x00};
        byte[] algorithmIdentifier = new byte[]{
                0x30, 0x0d,
                0x06, 0x09,
                0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        byte[] privateKey = derElement((byte) 0x04, pkcs1);
        return derElement((byte) 0x30, concat(version, algorithmIdentifier, privateKey));
    }

    private byte[] derElement(byte tag, byte[] value) {
        return concat(new byte[]{tag}, derLength(value.length), value);
    }

    private byte[] derLength(int length) {
        if (length < 128) {
            return new byte[]{(byte) length};
        }
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES).putInt(length);
        byte[] bytes = buffer.array();
        int firstNonZero = 0;
        while (firstNonZero < bytes.length && bytes[firstNonZero] == 0) {
            firstNonZero++;
        }
        int count = bytes.length - firstNonZero;
        byte[] result = new byte[count + 1];
        result[0] = (byte) (0x80 | count);
        System.arraycopy(bytes, firstNonZero, result, 1, count);
        return result;
    }

    private byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
