package com.eventhub.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import com.eventhub.security.EventHubJwtClaimsValidator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityConfigurationTest {

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void decodesOnlyValidHs256TokensWithFrozenIssuerAndRequiredClaims() {
        SecretKey key = key("a".repeat(32));
        JwtEncoder encoder = configuration.jwtEncoder(key);
        JwtDecoder decoder = configuration.jwtDecoder(key);
        Instant now = Instant.now();

        String token = encode(
                encoder,
                MacAlgorithm.HS256,
                "eventhub",
                "42",
                "alice",
                "USER",
                now.minusSeconds(1),
                now.plusSeconds(1_799));

        assertEquals("42", assertDoesNotThrow(() -> decoder.decode(token)).getSubject());
    }

    @Test
    void rejectsExpiredWrongSignatureWrongIssuerAndMalformedTokens() {
        SecretKey key = key("a".repeat(32));
        JwtEncoder encoder = configuration.jwtEncoder(key);
        JwtDecoder decoder = configuration.jwtDecoder(key);
        Instant now = Instant.now();
        String expired = encode(
                encoder, MacAlgorithm.HS256, "eventhub", "42", "alice", "USER",
                now.minusSeconds(1_801), now.minusSeconds(1));
        String wrongIssuer = encode(
                encoder, MacAlgorithm.HS256, "another-issuer", "42", "alice", "USER",
                now.minusSeconds(1), now.plusSeconds(1_799));
        String wrongSignature = encode(
                configuration.jwtEncoder(key("b".repeat(32))),
                MacAlgorithm.HS256, "eventhub", "42", "alice", "USER",
                now.minusSeconds(1), now.plusSeconds(1_799));

        assertThrows(JwtException.class, () -> decoder.decode(expired));
        assertThrows(JwtException.class, () -> decoder.decode(wrongIssuer));
        assertThrows(JwtException.class, () -> decoder.decode(wrongSignature));
        assertThrows(JwtException.class, () -> decoder.decode("not-a-jwt"));
    }

    @Test
    void rejectsMissingOrInvalidRequiredClaims() {
        SecretKey key = key("a".repeat(32));
        JwtEncoder encoder = configuration.jwtEncoder(key);
        JwtDecoder decoder = configuration.jwtDecoder(key);
        Instant now = Instant.now();

        assertThrows(JwtException.class, () -> decoder.decode(encode(
                encoder, MacAlgorithm.HS256, "eventhub", null, "alice", "USER",
                now.minusSeconds(1), now.plusSeconds(1_799))));
        assertThrows(JwtException.class, () -> decoder.decode(encode(
                encoder, MacAlgorithm.HS256, "eventhub", "not-a-number", "alice", "USER",
                now.minusSeconds(1), now.plusSeconds(1_799))));
        assertThrows(JwtException.class, () -> decoder.decode(encode(
                encoder, MacAlgorithm.HS256, "eventhub", "42", null, "USER",
                now.minusSeconds(1), now.plusSeconds(1_799))));
        assertThrows(JwtException.class, () -> decoder.decode(encode(
                encoder, MacAlgorithm.HS256, "eventhub", "42", "alice", "ROOT",
                now.minusSeconds(1), now.plusSeconds(1_799))));
        EventHubJwtClaimsValidator requiredClaimsValidator = new EventHubJwtClaimsValidator();
        assertEquals(true, requiredClaimsValidator.validate(jwtWithoutIssuedAt(now)).hasErrors());
        assertEquals(true, requiredClaimsValidator.validate(jwtWithoutExpiresAt(now)).hasErrors());
    }

    @Test
    void rejectsAValidHs512SignatureWhenDecoderIsFrozenToHs256() {
        SecretKey longKey = key("a".repeat(64));
        JwtEncoder encoder = configuration.jwtEncoder(longKey);
        JwtDecoder hs256OnlyDecoder = configuration.jwtDecoder(longKey);
        Instant now = Instant.now();
        String hs512 = encode(
                encoder, MacAlgorithm.HS512, "eventhub", "42", "alice", "USER",
                now.minusSeconds(1), now.plusSeconds(1_799));

        assertThrows(JwtException.class, () -> hs256OnlyDecoder.decode(hs512));
    }

    @Test
    void failsFastForMissingMalformedOrShortSigningSecret() {
        assertThrows(IllegalStateException.class, () -> configuration.eventHubJwtSecretKey(null));
        assertThrows(IllegalStateException.class, () -> configuration.eventHubJwtSecretKey(" "));
        assertThrows(IllegalStateException.class, () -> configuration.eventHubJwtSecretKey("not-base64"));
        assertThrows(IllegalStateException.class, () -> configuration.eventHubJwtSecretKey(
                Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8))));
        assertEquals(32, configuration.eventHubJwtSecretKey(
                Base64.getEncoder().encodeToString("a".repeat(32).getBytes(StandardCharsets.UTF_8)))
                .getEncoded().length);
    }

    private SecretKey key(String rawSecret) {
        return configuration.eventHubJwtSecretKey(
                Base64.getEncoder().encodeToString(rawSecret.getBytes(StandardCharsets.UTF_8)));
    }

    private static String encode(
            JwtEncoder encoder,
            MacAlgorithm algorithm,
            String issuer,
            String subject,
            String username,
            String role,
            Instant issuedAt,
            Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
        if (issuer != null) {
            claims.issuer(issuer);
        }
        if (subject != null) {
            claims.subject(subject);
        }
        if (username != null) {
            claims.claim("username", username);
        }
        if (role != null) {
            claims.claim("role", role);
        }
        if (issuedAt != null) {
            claims.issuedAt(issuedAt);
        }
        if (expiresAt != null) {
            claims.expiresAt(expiresAt);
        }
        JwsHeader header = JwsHeader.with(algorithm).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static org.springframework.security.oauth2.jwt.Jwt jwtWithoutIssuedAt(Instant now) {
        return org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token-without-iat")
                .header("alg", "HS256")
                .claim("iss", "eventhub")
                .subject("42")
                .claim("username", "alice")
                .claim("role", "USER")
                .expiresAt(now.plusSeconds(1_799))
                .build();
    }

    private static org.springframework.security.oauth2.jwt.Jwt jwtWithoutExpiresAt(Instant now) {
        return org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token-without-exp")
                .header("alg", "HS256")
                .claim("iss", "eventhub")
                .subject("42")
                .claim("username", "alice")
                .claim("role", "USER")
                .issuedAt(now.minusSeconds(1))
                .build();
    }
}
