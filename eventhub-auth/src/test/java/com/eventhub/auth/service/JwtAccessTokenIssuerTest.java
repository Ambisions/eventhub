package com.eventhub.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.eventhub.auth.api.model.AccessToken;
import com.eventhub.auth.api.model.AccessTokenContract;
import com.eventhub.user.api.model.UserAuthenticationData;
import com.eventhub.user.api.model.UserRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAccessTokenIssuerTest {

    @Test
    void issuesExactFrozenClaimsWithFixedClockAndRedactsTokenOutput() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        JwtEncoder encoder = mock(JwtEncoder.class);
        Jwt signedJwt = Jwt.withTokenValue("signed.jwt.value")
                .header("alg", "HS256")
                .claim("sub", "42")
                .build();
        when(encoder.encode(org.mockito.ArgumentMatchers.any())).thenReturn(signedJwt);
        JwtAccessTokenIssuer issuer = new JwtAccessTokenIssuer(encoder, clock);

        AccessToken token = issuer.issue(new UserAuthenticationData(
                42L,
                "alice",
                "{bcrypt}credential",
                UserRole.ADMIN));

        ArgumentCaptor<JwtEncoderParameters> parameters = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(encoder).encode(parameters.capture());
        assertEquals(MacAlgorithm.HS256, parameters.getValue().getJwsHeader().getAlgorithm());
        assertEquals("JWT", parameters.getValue().getJwsHeader().getType());
        assertEquals(AccessTokenContract.ISSUER, parameters.getValue().getClaims().getClaim("iss"));
        assertEquals("42", parameters.getValue().getClaims().getSubject());
        assertEquals("alice", parameters.getValue().getClaims().getClaim(AccessTokenContract.USERNAME_CLAIM));
        assertEquals("ADMIN", parameters.getValue().getClaims().getClaim(AccessTokenContract.ROLE_CLAIM));
        assertEquals(now, parameters.getValue().getClaims().getIssuedAt());
        assertEquals(now.plus(AccessTokenContract.TIME_TO_LIVE), parameters.getValue().getClaims().getExpiresAt());
        assertEquals(AccessTokenContract.TIME_TO_LIVE,
                java.time.Duration.between(
                        parameters.getValue().getClaims().getIssuedAt(),
                        parameters.getValue().getClaims().getExpiresAt()));
        assertEquals("signed.jwt.value", token.getTokenValue());
        assertEquals(now.plus(AccessTokenContract.TIME_TO_LIVE), token.getExpiresAt());
        assertFalse(token.toString().contains(token.getTokenValue()));
    }
}
