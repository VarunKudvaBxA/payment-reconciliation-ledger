create extension if not exists vector;

create table accounts (
    id             bigserial primary key,
    code           varchar(40) not null unique,
    name           varchar(120) not null,
    type           varchar(10) not null check (type in ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    allow_negative boolean not null default false,
    created_at     timestamptz not null default now()
);

-- net_debit is debits minus credits. Assets normally sit above zero, wallets (liabilities) below zero.
create table account_balances (
    account_id bigint primary key references accounts(id),
    net_debit  numeric(18,2) not null default 0
);

create table journal_entries (
    id              bigserial primary key,
    reference       varchar(80),
    description     text,
    idempotency_key varchar(80) unique,
    posted_at       timestamptz not null default now()
);
create index journal_entries_reference_idx on journal_entries (lower(reference));

create table ledger_lines (
    id         bigserial primary key,
    entry_id   bigint not null references journal_entries(id),
    account_id bigint not null references accounts(id),
    direction  char(1) not null check (direction in ('D', 'C')),
    amount     numeric(18,2) not null check (amount > 0)
);
create index ledger_lines_entry_idx on ledger_lines (entry_id);
create index ledger_lines_account_idx on ledger_lines (account_id);

-- debits must equal credits for every entry, checked when the transaction commits
create function check_entry_balanced() returns trigger as $$
declare
    d numeric;
    c numeric;
begin
    select coalesce(sum(amount) filter (where direction = 'D'), 0),
           coalesce(sum(amount) filter (where direction = 'C'), 0)
      into d, c
      from ledger_lines
     where entry_id = new.entry_id;
    if d <> c then
        raise exception 'journal entry % is not balanced (debits %, credits %)', new.entry_id, d, c;
    end if;
    return null;
end;
$$ language plpgsql;

create constraint trigger entry_must_balance
    after insert on ledger_lines
    deferrable initially deferred
    for each row execute function check_entry_balanced();

-- posted lines are never changed, corrections are new entries
create function ledger_lines_readonly() returns trigger as $$
begin
    raise exception 'ledger_lines is append only';
end;
$$ language plpgsql;

create trigger ledger_lines_no_change
    before update or delete on ledger_lines
    for each row execute function ledger_lines_readonly();

insert into accounts (code, name, type) values
    ('CASH_BANK',    'Operating bank account', 'ASSET'),
    ('FEE_INCOME',   'Fee income',             'REVENUE'),
    ('WALLET_ALICE', 'Alice wallet',           'LIABILITY'),
    ('WALLET_BOB',   'Bob wallet',             'LIABILITY'),
    ('WALLET_CAROL', 'Carol wallet',           'LIABILITY'),
    ('WALLET_DAVE',  'Dave wallet',            'LIABILITY');

insert into account_balances (account_id) select id from accounts;
