package com.eventhub.ticket.domain;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public final class TicketNumberGenerator {

    public String generate() {
        return "TKT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }
}
