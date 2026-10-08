package com.eventhub.application.flash;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public final class AdmissionIdGenerator {

    public String generate() {
        return UUID.randomUUID().toString();
    }
}
