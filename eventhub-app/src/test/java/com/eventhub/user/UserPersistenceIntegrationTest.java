package com.eventhub.user;

import java.util.UUID;

import com.eventhub.user.mapper.UserMapper;
import com.eventhub.user.repository.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserPersistenceIntegrationTest {

    @Autowired
    private UserMapper userMapper;

    @Test
    void insertsAndReadsUserWithRealMySqlMapping() {
        String username = "phase4-smoke-" + UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash("test-hash");
        user.setDisplayName("Persistence Smoke Test");

        int affectedRows = userMapper.insert(user);

        assertThat(affectedRows).isEqualTo(1);
        assertThat(user.getId()).isNotNull();

        UserEntity persistedUser = userMapper.selectById(user.getId());

        assertThat(persistedUser).isNotNull();
        assertThat(persistedUser.getId()).isEqualTo(user.getId());
        assertThat(persistedUser.getUsername()).isEqualTo(username);
        assertThat(persistedUser.getPasswordHash()).isEqualTo("test-hash");
        assertThat(persistedUser.getDisplayName()).isEqualTo("Persistence Smoke Test");
        assertThat(persistedUser.getCreatedAt()).isNotNull();
        assertThat(persistedUser.getUpdatedAt()).isNotNull();
    }
}
