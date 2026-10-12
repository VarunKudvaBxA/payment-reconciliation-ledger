create table statement_batches (
    id          bigserial primary key,
    filename    varchar(200) not null,
    uploaded_at timestamptz not null default now()
);

create table bank_statement_lines (
    id          bigserial primary key,
    batch_id    bigint not null references statement_batches(id),
    txn_date    date not null,
    amount      numeric(18,2) not null,
    description text not null default '',
    reference   varchar(120),
    embedding   vector(256),
    status      varchar(12) not null default 'UNMATCHED' check (status in ('UNMATCHED', 'MATCHED', 'IGNORED')),
    created_at  timestamptz not null default now()
);
create index bank_lines_batch_idx on bank_statement_lines (batch_id);
create index bank_lines_embedding_idx on bank_statement_lines using hnsw (embedding vector_cosine_ops);

alter table journal_entries add column embedding vector(256);
create index journal_entries_embedding_idx on journal_entries using hnsw (embedding vector_cosine_ops);

create table matches (
    id                bigserial primary key,
    statement_line_id bigint not null unique references bank_statement_lines(id),
    entry_id          bigint not null unique references journal_entries(id),
    method            varchar(10) not null check (method in ('EXACT', 'FUZZY', 'SEMANTIC', 'MANUAL')),
    confidence        numeric(4,3) not null,
    created_at        timestamptz not null default now()
);

create table anomalies (
    id                bigserial primary key,
    statement_line_id bigint references bank_statement_lines(id),
    entry_id          bigint references journal_entries(id),
    kind              varchar(24) not null,
    detail            text not null,
    created_at        timestamptz not null default now()
);

-- ledger entries that touch the bank account, with the amount as the bank would show it
create view bank_side_entries as
select e.id as entry_id,
       e.reference,
       e.description,
       e.posted_at::date as entry_date,
       sum(case l.direction when 'D' then l.amount else -l.amount end) as bank_amount
  from journal_entries e
  join ledger_lines l on l.entry_id = e.id
  join accounts a on a.id = l.account_id and a.code = 'CASH_BANK'
 group by e.id;
