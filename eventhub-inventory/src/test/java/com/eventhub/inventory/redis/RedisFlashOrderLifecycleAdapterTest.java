package com.eventhub.inventory.redis;

import java.util.List;

import com.eventhub.inventory.api.command.FlashOrderLifecycleCommand;
import com.eventhub.inventory.api.error.FlashAdmissionUnavailableException;
import com.eventhub.inventory.api.model.FlashOrderLifecycleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisFlashOrderLifecycleAdapterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisFlashOrderLifecycleAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RedisFlashOrderLifecycleAdapter(redisTemplate);
    }

    @Test
    void validatesCommandBeforeRedisExecution() {
        assertThrows(NullPointerException.class, () -> adapter.releaseActive(null));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.releaseActive(new FlashOrderLifecycleCommand(0, 2, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.releaseActive(new FlashOrderLifecycleCommand(1, 0, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.refundActive(new FlashOrderLifecycleCommand(1, 2, 0)));
    }

    @Test
    void mapsRepliesAndUsesExactKeysAndArguments() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("RELEASED", "REFUNDED");
        FlashOrderLifecycleCommand command = new FlashOrderLifecycleCommand(7, 8, 9);

        assertEquals(FlashOrderLifecycleResult.RELEASED, adapter.releaseActive(command));
        assertEquals(FlashOrderLifecycleResult.REFUNDED, adapter.refundActive(command));

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), keys.capture(), arguments.capture());
        assertEquals(List.of("eventhub:flash:{7}:meta", "eventhub:flash:{7}:admissions"),
                keys.getAllValues().get(0));
        assertArrayEquals(new Object[]{"RELEASE", "8", "9"}, arguments.getAllValues().get(0));
        assertArrayEquals(new Object[]{"REFUND", "8", "9"}, arguments.getAllValues().get(1));
    }

    @Test
    void translatesInfrastructureNullAndUnknownReplies() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("synthetic failure"))
                .thenReturn(null)
                .thenReturn("UNKNOWN");
        FlashOrderLifecycleCommand command = new FlashOrderLifecycleCommand(7, 8, 9);

        assertThrows(FlashAdmissionUnavailableException.class, () -> adapter.releaseActive(command));
        assertThrows(FlashAdmissionUnavailableException.class, () -> adapter.releaseActive(command));
        assertThrows(FlashAdmissionUnavailableException.class, () -> adapter.releaseActive(command));
    }
}
