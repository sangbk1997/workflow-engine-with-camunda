package com.truemoney.workflowengine.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ManagementController {

    private final InfoEndpoint infoEndpoint;

    @GetMapping("health")
    public HealthComponent getHealth() {
        return Health.up().build();
    }

    @GetMapping("info")
    public Map<String, Object> getInfo() {
        return infoEndpoint.info();
    }
}

