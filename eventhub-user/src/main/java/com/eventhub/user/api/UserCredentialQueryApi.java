package com.eventhub.user.api;

import java.util.Optional;

import com.eventhub.user.api.model.UserAuthenticationData;

public interface UserCredentialQueryApi {

    Optional<UserAuthenticationData> findByUsername(String username);
}
