package com.eventhub.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

public final class EventHubJwtAuthenticationToken extends AbstractAuthenticationToken {

    private final EventHubAuthenticatedUser principal;

    public EventHubJwtAuthenticationToken(
            EventHubAuthenticatedUser principal,
            Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public EventHubAuthenticatedUser getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.username();
    }
}
