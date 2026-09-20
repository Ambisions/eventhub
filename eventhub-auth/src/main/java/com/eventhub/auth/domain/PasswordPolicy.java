package com.eventhub.auth.domain;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import com.eventhub.auth.api.error.AuthErrorCode;
import com.eventhub.auth.api.error.AuthException;

public final class PasswordPolicy {

    private static final int MIN_CODE_POINTS = 8;

    private static final int MAX_CODE_POINTS = 64;

    private static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String rawPassword) {
        if (!isValid(rawPassword)) {
            throw new AuthException(AuthErrorCode.INVALID_PASSWORD);
        }
    }

    public static boolean isValid(String rawPassword) {
        if (rawPassword == null) {
            return false;
        }

        int codePointCount = rawPassword.codePointCount(0, rawPassword.length());
        if (codePointCount < MIN_CODE_POINTS || codePointCount > MAX_CODE_POINTS) {
            return false;
        }

        try {
            int encodedLength = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(rawPassword))
                    .remaining();
            return encodedLength <= MAX_UTF8_BYTES;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }
}
