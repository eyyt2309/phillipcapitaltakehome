package com.example.ledger.transaction;

import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class RequestHasher {

    public String hash(TransferRequest request) {
        String canonicalRequest = "TRANSFER|"
                + request.sourceAccountId() + "|"
                + request.destinationAccountId() + "|"
                + request.amount().setScale(4, RoundingMode.UNNECESSARY).toPlainString();
        return hash(canonicalRequest);
    }

    public String hashReversal(UUID originalTransactionId) {
        return hash("REVERSAL|" + originalTransactionId);
    }

    private String hash(String canonicalRequest) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
