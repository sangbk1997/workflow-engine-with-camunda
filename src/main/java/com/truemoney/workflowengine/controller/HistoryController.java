package com.truemoney.workflowengine.controller;

import lombok.RequiredArgsConstructor;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.history.HistoricTaskInstance;
import org.camunda.bpm.engine.history.HistoricVariableInstance;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    /**
     * Get process instance history
     */
    @GetMapping("/process-instance/{processInstanceId}")
    public HistoricProcessInstance getProcessInstanceHistory(@PathVariable String processInstanceId) {
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
    }

    /**
     * Get activity instances history for a process
     */
    @GetMapping("/process-instance/{processInstanceId}/activities")
    public List<HistoricActivityInstance> getActivityHistory(@PathVariable String processInstanceId) {
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceStartTime()
                .desc()
                .list();
    }

    /**
     * Get task history for a process
     */
    @GetMapping("/process-instance/{processInstanceId}/tasks")
    public List<HistoricTaskInstance> getTaskHistory(@PathVariable String processInstanceId) {
        return historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricTaskInstanceEndTime()
                .desc()
                .list();
    }

    /**
     * Get variable history for a process
     */
    @GetMapping("/process-instance/{processInstanceId}/variables")
    public List<HistoricVariableInstance> getVariableHistory(@PathVariable String processInstanceId) {
        return historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .list();
    }

    /**
     * Get all finished process instances
     */
    @GetMapping("/finished-processes")
    public List<HistoricProcessInstance> getFinishedProcesses(
            @RequestParam(required = false) String processDefinitionKey) {

        var query = historyService.createHistoricProcessInstanceQuery()
                .finished();

        if (processDefinitionKey != null) {
            query.processDefinitionKey(processDefinitionKey);
        }

        return query.orderByProcessInstanceEndTime()
                .desc()
                .list();
    }

    /**
     * Get all unfinished (running) process instances
     */
    @GetMapping("/running-processes")
    public List<HistoricProcessInstance> getRunningProcesses(
            @RequestParam(required = false) String processDefinitionKey) {

        var query = historyService.createHistoricProcessInstanceQuery()
                .unfinished();

        if (processDefinitionKey != null) {
            query.processDefinitionKey(processDefinitionKey);
        }

        return query.orderByProcessInstanceStartTime()
                .desc()
                .list();
    }
}

