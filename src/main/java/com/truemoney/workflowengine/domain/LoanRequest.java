package com.truemoney.workflowengine.domain;

import lombok.Data;

@Data
public class LoanRequest {
    private String applicant;
    private double amount;
    // getters and setters
}