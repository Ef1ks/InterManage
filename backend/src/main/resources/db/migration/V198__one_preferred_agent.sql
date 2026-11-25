-- Preferowany agent jest najwyżej jeden w sklepie (dotąd dało się włączyć kilka). Tam, gdzie jest kilka, zostaje
-- obecny główny agent sklepu, a bez niego aktywny z najświeższym kontaktem.
update store_agents a
set preferred = false
where a.preferred
  and a.id <> (
    select b.id
    from store_agents b
    left join store_agent_leaders l on l.agent_id = b.id and l.store_id = b.store_id
    where b.store_id = a.store_id and b.preferred
    order by (b.deleted_at is null) desc, (l.agent_id is not null) desc, b.last_seen_at desc nulls last, b.id
    limit 1);

create unique index store_agents_preferred_uq on store_agents (store_id) where preferred;
