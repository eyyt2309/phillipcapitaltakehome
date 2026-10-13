package com.example.ledger.transaction;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class FixedExchangeRateProvider implements ExchangeRateProvider {

    private static final Map<CurrencyPair, BigDecimal> RATES = Map.of(
            new CurrencyPair("USD", "SGD"), new BigDecimal("1.35000000"),
            new CurrencyPair("SGD", "USD"), new BigDecimal("0.74074074")
    );

    @Override
    public BigDecimal getRate(String sourceCurrency, String destinationCurrency) {
        BigDecimal rate = RATES.get(new CurrencyPair(sourceCurrency, destinationCurrency));
        if (rate == null) {
            throw new UnsupportedCurrencyPairException(sourceCurrency, destinationCurrency);
        }
        return rate;
    }

    private record CurrencyPair(String sourceCurrency, String destinationCurrency) {
    }
}
