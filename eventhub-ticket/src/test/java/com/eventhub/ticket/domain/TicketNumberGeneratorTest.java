package com.eventhub.ticket.domain;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketNumberGeneratorTest {

    private static final Pattern TICKET_NUMBER_PATTERN = Pattern.compile("^TKT-[0-9A-F]{32}$");

    private final TicketNumberGenerator generator = new TicketNumberGenerator();

    @Test
    void generatesCanonicalDistinctTicketNumbers() {
        Set<String> generated = new HashSet<>();

        for (int index = 0; index < 20; index++) {
            String ticketNo = generator.generate();
            assertEquals(36, ticketNo.length());
            assertTrue(ticketNo.startsWith("TKT-"));
            assertTrue(TICKET_NUMBER_PATTERN.matcher(ticketNo).matches());
            assertTrue(generated.add(ticketNo));
        }
    }
}
