package com.eventhub.security;

import java.time.Instant;

import com.eventhub.user.api.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class EventHubJwtAuthenticationConverterTest {

    @Test
    void mapsValidatedRoleClaimToTrustedPrincipalAndAuthorityWithoutRetainingCredentials() {
        String tokenValue = "sensitive.jwt.value";
        Jwt jwt = Jwt.withTokenValue(tokenValue)
                .header("alg", "HS256")
                .issuer("eventhub")
                .subject("42")
                .issuedAt(Instant.parse("2030-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2030-01-01T00:30:00Z"))
                .claim("username", "alice")
                .claim("role", "ADMIN")
                .build();

        EventHubJwtAuthenticationToken authentication =
                new EventHubJwtAuthenticationConverter().convert(jwt);

        assertThat(authentication.getPrincipal())
                .isEqualTo(new EventHubAuthenticatedUser(42L, "alice", UserRole.ADMIN));
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.toString()).doesNotContain(tokenValue);
    }
}
