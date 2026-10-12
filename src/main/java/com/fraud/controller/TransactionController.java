package com.fraud.controller;

import com.fraud.dto.TransactionRequest;
import com.fraud.dto.VelocityCheckResult;
import com.fraud.service.VelocityCheckingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final VelocityCheckingService velocityCheckingService;

    public TransactionController(VelocityCheckingService velocityCheckingService) {
        this.velocityCheckingService = velocityCheckingService;
    }

    @PostMapping("/evaluate")
    public ResponseEntity<VelocityCheckResult> evaluateTransaction(@Valid @RequestBody TransactionRequest request) {
        VelocityCheckResult result = velocityCheckingService.checkTransactionVelocity(request);
        return ResponseEntity.status(result.limitExceeded() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.OK)
                .body(result);
    }
}
