package com.storeanalytics.sync.web;

import com.storeanalytics.sync.service.EmployeeSyncResult;
import com.storeanalytics.sync.service.EmployeeSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class EmployeeSyncController {

    private final EmployeeSyncService employeeSyncService;

    public EmployeeSyncController(EmployeeSyncService employeeSyncService) {
        this.employeeSyncService = employeeSyncService;
    }

    @PostMapping("/employees")
    @ResponseStatus(HttpStatus.OK)
    EmployeeSyncResult synchronizeEmployees() {
        return employeeSyncService.synchronize();
    }
}
