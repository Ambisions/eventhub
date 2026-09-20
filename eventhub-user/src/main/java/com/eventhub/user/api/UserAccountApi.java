package com.eventhub.user.api;

import com.eventhub.user.api.command.CreateUserAccountCommand;
import com.eventhub.user.api.command.UpdateDisplayNameCommand;
import com.eventhub.user.api.model.UserProfile;

public interface UserAccountApi {

    UserProfile createAccount(CreateUserAccountCommand command);

    UserProfile getProfile(long userId);

    UserProfile updateDisplayName(long userId, UpdateDisplayNameCommand command);
}
