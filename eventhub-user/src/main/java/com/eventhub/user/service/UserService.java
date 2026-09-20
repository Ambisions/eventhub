package com.eventhub.user.service;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.eventhub.user.api.UserAccountApi;
import com.eventhub.user.api.UserCredentialQueryApi;
import com.eventhub.user.api.command.CreateUserAccountCommand;
import com.eventhub.user.api.command.UpdateDisplayNameCommand;
import com.eventhub.user.api.error.UserErrorCode;
import com.eventhub.user.api.error.UserException;
import com.eventhub.user.api.model.UserAuthenticationData;
import com.eventhub.user.api.model.UserProfile;
import com.eventhub.user.api.model.UserRole;
import com.eventhub.user.domain.DisplayNamePolicy;
import com.eventhub.user.domain.UsernamePolicy;
import com.eventhub.user.mapper.UserMapper;
import com.eventhub.user.repository.UserEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService implements UserAccountApi, UserCredentialQueryApi {

    private static final int MAX_ENCODED_PASSWORD_LENGTH = 255;

    private static final String USERNAME_UNIQUE_CONSTRAINT = "uk_users_username";

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    @Transactional
    public UserProfile createAccount(CreateUserAccountCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        String username = UsernamePolicy.canonicalize(command.getUsername());
        String encodedPassword = validateEncodedPassword(command.getEncodedPassword());
        String displayName = DisplayNamePolicy.normalize(command.getDisplayName());

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(encodedPassword);
        user.setDisplayName(displayName);
        user.setRole(UserRole.USER);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            if (isUsernameDuplicate(exception)) {
                throw new UserException(UserErrorCode.USERNAME_ALREADY_EXISTS, exception);
            }
            throw exception;
        }

        return toProfile(requireUser(user.getId()));
    }

    @Override
    public UserProfile getProfile(long userId) {
        return toProfile(requireUser(userId));
    }

    @Override
    @Transactional
    public UserProfile updateDisplayName(long userId, UpdateDisplayNameCommand command) {
        validateUserId(userId);
        Objects.requireNonNull(command, "command must not be null");

        String displayName = DisplayNamePolicy.normalize(command.getDisplayName());
        int affectedRows = userMapper.update(
                null,
                Wrappers.<UserEntity>lambdaUpdate()
                        .eq(UserEntity::getId, userId)
                        .set(UserEntity::getDisplayName, displayName));
        if (affectedRows == 0) {
            throw new UserException(UserErrorCode.USER_NOT_FOUND);
        }
        return toProfile(requireUser(userId));
    }

    @Override
    public Optional<UserAuthenticationData> findByUsername(String username) {
        String canonicalUsername = UsernamePolicy.canonicalize(username);
        UserEntity user = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery()
                        .eq(UserEntity::getUsername, canonicalUsername));
        if (user == null) {
            return Optional.empty();
        }
        return Optional.of(new UserAuthenticationData(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getRole()));
    }

    private UserEntity requireUser(long userId) {
        validateUserId(userId);
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new UserException(UserErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private static void validateUserId(long userId) {
        if (userId <= 0) {
            throw new UserException(UserErrorCode.USER_NOT_FOUND);
        }
    }

    private static String validateEncodedPassword(String encodedPassword) {
        if (encodedPassword == null
                || encodedPassword.isBlank()
                || encodedPassword.length() > MAX_ENCODED_PASSWORD_LENGTH) {
            throw new UserException(UserErrorCode.INVALID_ENCODED_PASSWORD);
        }
        return encodedPassword;
    }

    private static boolean isUsernameDuplicate(DuplicateKeyException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains(USERNAME_UNIQUE_CONSTRAINT)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static UserProfile toProfile(UserEntity user) {
        return new UserProfile(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
