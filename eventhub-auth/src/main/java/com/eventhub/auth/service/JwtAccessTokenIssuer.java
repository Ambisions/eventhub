package com.eventhub.auth.service;

import java.time.Clock;
import java.time.Instant;

import com.eventhub.auth.api.model.AccessToken;
import com.eventhub.auth.api.model.AccessTokenContract;
import com.eventhub.user.api.model.UserAuthenticationData;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public final class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder jwtEncoder;

    private final Clock clock;

    public JwtAccessTokenIssuer(JwtEncoder jwtEncoder, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
    }

    @Override
    public AccessToken issue(UserAuthenticationData authenticatedUser) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(AccessTokenContract.TIME_TO_LIVE);
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AccessTokenContract.ISSUER)
                .subject(Long.toString(authenticatedUser.getUserId()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(AccessTokenContract.USERNAME_CLAIM, authenticatedUser.getUsername())
                .claim(AccessTokenContract.ROLE_CLAIM, authenticatedUser.getRole().name())
                .build();

        Jwt jwt = jwtEncoder.encode(JwtEncoderParameters.from(header, claims));
        return new AccessToken(jwt.getTokenValue(), expiresAt);
    }
}
