package com.eventhub.controller.auth;

import com.eventhub.auth.api.AuthApi;
import com.eventhub.auth.api.command.LoginCommand;
import com.eventhub.auth.api.command.RegisterCommand;
import com.eventhub.controller.auth.dto.AccessTokenResponse;
import com.eventhub.controller.auth.dto.LoginRequest;
import com.eventhub.controller.auth.dto.RegisterRequest;
import com.eventhub.controller.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public final class AuthController {

    private final AuthApi authApi;

    public AuthController(AuthApi authApi) {
        this.authApi = authApi;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(authApi.register(
                new RegisterCommand(request.getUsername(), request.getPassword(), request.getDisplayName()))));
    }

    @PostMapping("/login")
    public AccessTokenResponse login(@Valid @RequestBody LoginRequest request) {
        return AccessTokenResponse.from(authApi.login(
                new LoginCommand(request.getUsername(), request.getPassword())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }
}
