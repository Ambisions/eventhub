package com.eventhub.auth.api;

import com.eventhub.auth.api.command.LoginCommand;
import com.eventhub.auth.api.command.RegisterCommand;
import com.eventhub.auth.api.model.AccessToken;
import com.eventhub.user.api.model.UserProfile;

public interface AuthApi {

    UserProfile register(RegisterCommand command);

    AccessToken login(LoginCommand command);
}
