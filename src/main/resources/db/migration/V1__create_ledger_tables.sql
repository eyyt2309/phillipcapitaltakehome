CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    opening_balance NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (opening_balance >= 0),
    balance NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    source_account_id UUID NOT NULL REFERENCES accounts(id),
    destination_account_id UUID NOT NULL REFERENCES accounts(id),
    source_amount NUMERIC(19,4) NOT NULL CHECK (source_amount > 0),
    source_currency VARCHAR(3) NOT NULL CHECK (source_currency ~ '^[A-Z]{3}$'),
    destination_amount NUMERIC(19,4) NOT NULL CHECK (destination_amount > 0),
    destination_currency VARCHAR(3) NOT NULL CHECK (destination_currency ~ '^[A-Z]{3}$'),
    exchange_rate NUMERIC(19,8),
    transaction_type VARCHAR(20) NOT NULL
        CHECK (transaction_type IN ('TRANSFER', 'REVERSAL')),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    reversal_of UUID UNIQUE REFERENCES ledger_transactions(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (source_account_id <> destination_account_id),
    CHECK (
        (transaction_type = 'TRANSFER' AND reversal_of IS NULL)
        OR
        (transaction_type = 'REVERSAL' AND reversal_of IS NOT NULL)
    ),
    CONSTRAINT ledger_transactions_exchange_rate_consistency CHECK (
        (source_currency = destination_currency AND exchange_rate IS NULL)
        OR
        (
            source_currency <> destination_currency
            AND exchange_rate IS NOT NULL
            AND exchange_rate > 0
        )
    )
);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transactions(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19,4) NOT NULL CHECK (amount <> 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entries_account_time_idx
    ON ledger_entries (account_id, created_at, transaction_id);
CREATE INDEX ledger_entries_transaction_idx
    ON ledger_entries (transaction_id);
