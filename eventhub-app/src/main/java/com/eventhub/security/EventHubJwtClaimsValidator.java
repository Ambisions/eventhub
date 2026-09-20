package com.eventhub.security;

import com.eventhub.auth.api.model.AccessTokenContract;
import com.eventhub.user.api.model.UserRole;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class EventHubJwtClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_REQUIRED_CLAIMS = new OAuth2Error(
            "invalid_token",
            "The access token does not contain valid required claims",
            null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!hasValidSubject(jwt)
                || !hasNonBlankStringClaim(jwt, AccessTokenContract.USERNAME_CLAIM)
                || !hasValidRole(jwt)
                || jwt.getIssuedAt() == null
                || jwt.getExpiresAt() == null
                || !AccessTokenContract.ISSUER.equals(jwt.getClaimAsString("iss"))
                || !jwt.getIssuedAt().isBefore(jwt.getExpiresAt())) {
            return OAuth2TokenValidatorResult.failure(INVALID_REQUIRED_CLAIMS);
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static boolean hasValidSubject(Jwt jwt) {
        try {
            return jwt.getSubject() != null && Long.parseLong(jwt.getSubject()) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean hasNonBlankStringClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaims().get(claimName);
        return value instanceof String stringValue && !stringValue.isBlank();
    }

    private static boolean hasValidRole(Jwt jwt) {
        Object value = jwt.getClaims().get(AccessTokenContract.ROLE_CLAIM);
        if (!(value instanceof String roleValue)) {
            return false;
        }
        try {
            UserRole.valueOf(roleValue);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
