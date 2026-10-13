package com.example.ledger.common;

import com.example.ledger.account.AccountNotFoundException;
import com.example.ledger.reconciliation.InvalidAsOfDateException;
import com.example.ledger.transaction.CurrencyMismatchException;
import com.example.ledger.transaction.DuplicateReversalException;
import com.example.ledger.transaction.IdempotencyConflictException;
import com.example.ledger.transaction.InsufficientFundsException;
import com.example.ledger.transaction.ReversalNotAllowedException;
import com.example.ledger.transaction.SelfTransferException;
import com.example.ledger.transaction.TransactionNotFoundException;
import com.example.ledger.transaction.UnsupportedCurrencyPairException;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(
            AccountNotFoundException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );
        problem.setTitle("Account not found");
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        problem.setProperty("code", "NOT_FOUND");
        return problem;
    }

    @ExceptionHandler(SelfTransferException.class)
    public ProblemDetail handleSelfTransfer(
            SelfTransferException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT.value(),
                "Self-transfer not allowed",
                "SELF_TRANSFER",
                exception,
                request
        );
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(
            InsufficientFundsException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT.value(),
                "Insufficient funds",
                "INSUFFICIENT_FUNDS",
                exception,
                request
        );
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(
            CurrencyMismatchException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Currency mismatch",
                "CURRENCY_MISMATCH",
                exception,
                request
        );
    }

    @ExceptionHandler(UnsupportedCurrencyPairException.class)
    public ProblemDetail handleUnsupportedCurrencyPair(
            UnsupportedCurrencyPairException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "Unsupported currency pair",
                "UNSUPPORTED_CURRENCY_PAIR",
                exception,
                request
        );
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT.value(),
                "Idempotency key reused",
                "IDEMPOTENCY_KEY_REUSED",
                exception,
                request
        );
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ProblemDetail handleTransactionNotFound(
            TransactionNotFoundException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.NOT_FOUND.value(),
                "Transaction not found",
                "TRANSACTION_NOT_FOUND",
                exception,
                request
        );
    }

    @ExceptionHandler(DuplicateReversalException.class)
    public ProblemDetail handleDuplicateReversal(
            DuplicateReversalException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT.value(),
                "Transaction already reversed",
                "DUPLICATE_REVERSAL",
                exception,
                request
        );
    }

    @ExceptionHandler(ReversalNotAllowedException.class)
    public ProblemDetail handleReversalNotAllowed(
            ReversalNotAllowedException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT.value(),
                "Reversal not allowed",
                "REVERSAL_NOT_ALLOWED",
                exception,
                request
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed."
        );
        problem.setTitle("Invalid request");
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        problem.setProperty("code", "INVALID_REQUEST");
        return problem;
    }

    @ExceptionHandler(InvalidAsOfDateException.class)
    public ProblemDetail handleInvalidAsOfDate(
            InvalidAsOfDateException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.BAD_REQUEST.value(),
                "Invalid asOfDate",
                "INVALID_AS_OF_DATE",
                exception,
                request
        );
    }

    private ProblemDetail problem(
            int status,
            String title,
            String code,
            RuntimeException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatusCode.valueOf(status),
                exception.getMessage()
        );
        problem.setTitle(title);
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return problem;
    }
}
