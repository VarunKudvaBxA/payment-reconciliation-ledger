package com.recon.ledger;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String accountCode) {
        super("Not enough balance in " + accountCode);
    }
}
