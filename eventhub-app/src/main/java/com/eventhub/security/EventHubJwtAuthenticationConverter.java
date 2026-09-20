package com.eventhub.security;

import java.util.List;

import com.eventhub.auth.api.model.AccessTokenContract;
import com.eventhub.user.api.model.UserRole;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public final class EventHubJwtAuthenticationConverter
        implements Converter<Jwt, EventHubJwtAuthenticationToken> {

    @Override
    public EventHubJwtAuthenticationToken convert(Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        String username = jwt.getClaimAsString(AccessTokenContract.USERNAME_CLAIM);
        UserRole role = UserRole.valueOf(jwt.getClaimAsString(AccessTokenContract.ROLE_CLAIM));
        EventHubAuthenticatedUser principal = new EventHubAuthenticatedUser(userId, username, role);
        return new EventHubJwtAuthenticationToken(
                principal,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }
}
