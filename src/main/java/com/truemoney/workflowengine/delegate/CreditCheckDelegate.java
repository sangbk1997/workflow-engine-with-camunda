package com.truemoney.workflowengine.delegate;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

@Component
public class CreditCheckDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {

        Integer creditScore =
                (Integer) execution.getVariable("creditScore");

        if (creditScore == null) {
            creditScore = 600; // default demo
        }

        boolean approved = creditScore >= 700;

        execution.setVariable("approved", approved);

        System.out.println("Credit score = " + creditScore
                + " | approved = " + approved);
    }
}
