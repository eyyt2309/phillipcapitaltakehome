package com.example.ledger.transaction;

import java.math.BigDecimal;

public interface ExchangeRateProvider {

    BigDecimal getRate(String sourceCurrency, String destinationCurrency);
}
