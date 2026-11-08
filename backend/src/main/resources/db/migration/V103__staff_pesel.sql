-- PESEL w Kadrach (do eksportu godzin i importu z programu kadrowego). Zaszyfrowany kluczem aplikacji; odcisk HMAC
-- pozwala sprawdzić powtórzenie w firmie bez odszyfrowywania, a sam z siebie nie zdradza numeru.
alter table staff_profiles add column pesel_encrypted text;
alter table staff_profiles add column pesel_fingerprint varchar(64);

create index staff_profiles_pesel_fingerprint_idx on staff_profiles (pesel_fingerprint)
    where pesel_fingerprint is not null;
