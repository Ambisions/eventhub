package com.eventhub.auth.service;

import com.eventhub.auth.api.model.AccessToken;
import com.eventhub.user.api.model.UserAuthenticationData;

public interface AccessTokenIssuer {

    AccessToken issue(UserAuthenticationData authenticatedUser);
}
