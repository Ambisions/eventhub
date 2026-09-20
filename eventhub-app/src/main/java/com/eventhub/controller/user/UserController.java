package com.eventhub.controller.user;

import com.eventhub.controller.user.dto.UpdateCurrentUserRequest;
import com.eventhub.controller.user.dto.UserResponse;
import com.eventhub.security.EventHubAuthenticatedUser;
import com.eventhub.user.api.UserAccountApi;
import com.eventhub.user.api.command.UpdateDisplayNameCommand;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
public final class UserController {

    private final UserAccountApi userAccountApi;

    public UserController(UserAccountApi userAccountApi) {
        this.userAccountApi = userAccountApi;
    }

    @GetMapping
    public UserResponse getCurrentUser(@AuthenticationPrincipal EventHubAuthenticatedUser currentUser) {
        return UserResponse.from(userAccountApi.getProfile(currentUser.userId()));
    }

    @PutMapping
    public UserResponse updateCurrentUser(
            @AuthenticationPrincipal EventHubAuthenticatedUser currentUser,
            @RequestBody UpdateCurrentUserRequest request) {
        return UserResponse.from(userAccountApi.updateDisplayName(
                currentUser.userId(),
                new UpdateDisplayNameCommand(request.getDisplayName())));
    }
}
