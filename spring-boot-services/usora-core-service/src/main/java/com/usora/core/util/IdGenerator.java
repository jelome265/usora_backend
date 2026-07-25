package com.usora.core.util;

import com.fasterxml.uuid.Generators;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class IdGenerator {

    public UUID generateV7() {
        return Generators.timeBasedEpochGenerator().generate();
    }

    public String generateCaseId() {
        return generateV7().toString();
    }

    public String generateRequestId() {
        return generateV7().toString();
    }
}
