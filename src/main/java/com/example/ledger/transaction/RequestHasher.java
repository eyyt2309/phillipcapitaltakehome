package com.example.ledger.transaction;

import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class RequestHasher {

    public String hash(TransferRequest request) {
        String canonicalRequest = "TRANSFER|"
                + request.sourceAccountId() + "|"
                + request.destinationAccountId() + "|"
                + request.amount().setScale(4, RoundingMode.UNNECESSARY).toPlainString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
