package com.truemoney.workflowengine.delegate;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component
public class LoanCheckDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
        double amount = (double) execution.getVariable("amount");
        boolean eligible = amount <= 50000; // Ví dụ điều kiện
        execution.setVariable("eligible", eligible);
    }
}