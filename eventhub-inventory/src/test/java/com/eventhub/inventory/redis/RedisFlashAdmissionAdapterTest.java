package com.eventhub.inventory.redis;

import java.util.List;

import com.eventhub.inventory.api.command.ActivateFlashAdmissionCommand;
import com.eventhub.inventory.api.command.PrepareFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReleasePendingFlashAdmissionCommand;
import com.eventhub.inventory.api.command.ReserveFlashAdmissionCommand;
import com.eventhub.inventory.api.error.FlashAdmissionUnavailableException;
import com.eventhub.inventory.api.model.FlashAdmissionResult;
import com.eventhub.inventory.api.model.FlashPrepareResult;
import com.eventhub.inventory.api.model.FlashReservationSettleResult;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisFlashAdmissionAdapterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisFlashAdmissionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RedisFlashAdmissionAdapter(redisTemplate);
    }

    @Test
    void validatesCommandsBeforeRedisExecution() {
        assertThrows(NullPointerException.class, () -> adapter.prepare(null));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare(new PrepareFlashAdmissionCommand(0, 1, 10, 20)));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare(new PrepareFlashAdmissionCommand(1, -1, 10, 20)));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.prepare(new PrepareFlashAdmissionCommand(1, 1, 20, 20)));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.reserve(new ReserveFlashAdmissionCommand(1, 0, "admission")));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.reserve(new ReserveFlashAdmissionCommand(1, 2, " ")));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.activate(new ActivateFlashAdmissionCommand(1, 2, "admission", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.releasePending(new ReleasePendingFlashAdmissionCommand(1, 2, null)));
    }

    @Test
    void mapsExactRepliesAndUsesExactHashTaggedKeys() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("PREPARED", "ADMITTED", "ACTIVATED", "RELEASED");

        assertEquals(FlashPrepareResult.PREPARED,
                adapter.prepare(new PrepareFlashAdmissionCommand(7, 3, 10, 20)));
        assertEquals(FlashAdmissionResult.ADMITTED,
                adapter.reserve(new ReserveFlashAdmissionCommand(7, 8, "admission-1")));
        assertEquals(FlashReservationSettleResult.ACTIVATED,
                adapter.activate(new ActivateFlashAdmissionCommand(7, 8, "admission-1", 9)));
        assertEquals(FlashReservationSettleResult.RELEASED,
                adapter.releasePending(new ReleasePendingFlashAdmissionCommand(7, 8, "admission-1")));

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.times(4))
                .execute(any(RedisScript.class), keys.capture(), arguments.capture());
        assertEquals(List.of("eventhub:flash:{7}:meta", "eventhub:flash:{7}:admissions"),
                keys.getAllValues().get(0));
        assertArrayEquals(new Object[]{"3", "10", "20"}, arguments.getAllValues().get(0));
        assertArrayEquals(new Object[]{"ACTIVATE", "8", "admission-1", "9"},
                arguments.getAllValues().get(2));
    }

    @Test
    void translatesInfrastructureNullAndUnknownReplies() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("synthetic Redis failure"))
                .thenReturn(null)
                .thenReturn("UNKNOWN");

        PrepareFlashAdmissionCommand command = new PrepareFlashAdmissionCommand(7, 3, 10, 20);
        assertThrows(FlashAdmissionUnavailableException.class, () -> adapter.prepare(command));
        assertThrows(FlashAdmissionUnavailableException.class, () -> adapter.prepare(command));
        assertThrows(FlashAdmissionUnavailableException.class, () -> adapter.prepare(command));
    }
}
