package com.truemoney.workflowengine.controller;
import com.truemoney.workflowengine.domain.LoanRequest;
import com.truemoney.workflowengine.service.ProcessStartService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/process")
@RequiredArgsConstructor
public class ProcessController {

    private final ProcessStartService processStartService;

    @PostMapping("/start")
    public String start(@RequestParam Integer creditScore) {

        processStartService.startProcess(creditScore);
        return "Process started with creditScore=" + creditScore;
    }

    @PostMapping("/loan/apply")
    public String applyLoan(@RequestBody LoanRequest request) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("applicant", request.getApplicant());
        variables.put("amount", request.getAmount());
        processStartService.applyLoan(variables);
        return "Loan application submitted!";
    }
}
