-- Organisations and the currency registry.
--
-- Every business record in CloFin belongs to an organisation. The currency
-- table is reference data rather than configuration: a foreign key from every
-- monetary column means an unknown currency cannot be persisted, and the scale
-- is available to any reporting query without the application.

create table organisation (
  id          uuid        primary key,
  legal_name  text        not null,
  short_name  text        not null,
  status      text        not null default 'active',
  created_at  timestamptz not null default now(),

  constraint organisation_status_known
    check (status in ('active', 'suspended', 'closed')),
  constraint organisation_legal_name_present
    check (length(btrim(legal_name)) > 0)
);

create unique index organisation_short_name_key on organisation (lower(short_name));

comment on table organisation is
  'A tenant of the platform. All synthetic; no real legal entity is represented.';

-- ---------------------------------------------------------------------------
-- Currency registry
-- ---------------------------------------------------------------------------

create table currency (
  code   char(3) primary key,
  scale  smallint not null,
  name   text     not null,

  constraint currency_code_uppercase check (code = upper(code)),
  constraint currency_scale_range    check (scale between 0 and 4)
);

comment on column currency.scale is
  'ISO 4217 decimal places. One major unit is 10^scale minor units. Not every '
  'currency has two: JPY and KRW have none, BHD, KWD and TND have three.';

insert into currency (code, scale, name) values
  ('AUD', 2, 'Australian Dollar'),
  ('BHD', 3, 'Bahraini Dinar'),
  ('CAD', 2, 'Canadian Dollar'),
  ('CHF', 2, 'Swiss Franc'),
  ('CNY', 2, 'Yuan Renminbi'),
  ('EUR', 2, 'Euro'),
  ('GBP', 2, 'Pound Sterling'),
  ('HKD', 2, 'Hong Kong Dollar'),
  ('IDR', 2, 'Rupiah'),
  ('INR', 2, 'Indian Rupee'),
  ('JPY', 0, 'Yen'),
  ('KRW', 0, 'Won'),
  ('KWD', 3, 'Kuwaiti Dinar'),
  ('MYR', 2, 'Malaysian Ringgit'),
  ('NZD', 2, 'New Zealand Dollar'),
  ('PHP', 2, 'Philippine Peso'),
  ('SGD', 2, 'Singapore Dollar'),
  ('THB', 2, 'Baht'),
  ('TND', 3, 'Tunisian Dinar'),
  ('USD', 2, 'US Dollar'),
  ('VND', 0, 'Dong');
