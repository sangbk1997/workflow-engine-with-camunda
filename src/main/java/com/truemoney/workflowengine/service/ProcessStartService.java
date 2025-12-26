package com.truemoney.workflowengine.service;
import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProcessStartService {

    private final RuntimeService runtimeService;

    public void startProcess(Integer creditScore) {

        runtimeService.startProcessInstanceByKey(
                "checkCreditProcess",
                Map.of("creditScore", creditScore)
        );
    }

    public void applyLoan(Map<String, Object> variables) {

        runtimeService.startProcessInstanceByKey("loanApprovalApp", variables);
    }
}
