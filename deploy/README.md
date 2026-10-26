# Wdrożenie na produkcję (https://intermanage.pl)

```
push na main → GitHub Actions: testy → obrazy ghcr.io/ef1ks/intermanage-{backend,frontend}
             → Tailscale (subnet router na hoście pve) → SSH do LXC 192.168.33.113 → docker compose pull && up -d
internet → Cloudflare (HTTPS) → tunel cloudflared w LXC → nginx (web) → /api → backend → PostgreSQL (LXC 192.168.33.110)
```

## 0. PostgreSQL (osobny LXC 192.168.33.110)

Baza musi przyjmować połączenia z LXC aplikacji: `listen_addresses = '*'` (albo adres LXC) w `postgresql.conf` i w `pg_hba.conf`:

```
host  intermanage_prod  intermanage_prod  192.168.33.113/32  scram-sha-256
```

Produkcja ma własną bazę i użytkownika, oddzielnie od bazy, na której pracuje się lokalnie (lokalny `spring-boot:run` nie może migrować produkcji):

```sql
CREATE USER intermanage_prod WITH PASSWORD '...';
CREATE DATABASE intermanage_prod OWNER intermanage_prod;
```

Schemat zakłada i aktualizuje sam backend (Flyway przy starcie), wystarczy pusta baza.

Serwer nie ma otwartych portów na routerze: GitHub wchodzi przez Tailscale, użytkownicy przez Cloudflare Tunnel.

## 1. Kontener LXC w Proxmoxie

Stały adres `192.168.33.113/24`, DNS z hosta. Docker potrzebuje w Options → Features (albo w `/etc/pve/lxc/<ID>.conf`, kontener wyłączony):

```
features: nesting=1,keyctl=1
```

Tailscale'a w kontenerze nie instalujemy: GitHub dociera do niego przez subnet router na hoście `pve`.

## 2. W kontenerze

```bash
curl -fsSL https://get.docker.com | sh

adduser --disabled-password deploy
usermod -aG docker deploy
mkdir -p /opt/intermanage && chown deploy:deploy /opt/intermanage
```

Klucz do wdrożeń (wygeneruj u siebie, bez hasła):

```bash
ssh-keygen -t ed25519 -N "" -C github-deploy -f intermanage-deploy
```

`intermanage-deploy.pub` dopisz do `/home/deploy/.ssh/authorized_keys` (katalog 700, plik 600, właściciel `deploy`).

Plik `/opt/intermanage/.env` z wzoru [.env.example](.env.example), `chmod 600`. `JWT_SECRET` i `APP_SECRET_KEY` generuje się raz i już się ich nie zmienia.

## 3. Cloudflare

1. Domena `intermanage.pl` musi być w Cloudflare (nameservery u rejestratora wskazują na Cloudflare).
2. Zero Trust → Networks → Tunnels → Create a tunnel (Cloudflared) → skopiuj token do `TUNNEL_TOKEN` w `.env`.
3. W tunelu Public Hostname: `intermanage.pl` (opcjonalnie też `www`), Service `HTTP`, URL `web:80`. Rekord DNS Cloudflare doda sam.

## 4. Tailscale (dostęp dla GitHub Actions)

1. Host `pve` udostępnia podsieć (`tailscale up --advertise-routes=192.168.33.0/24`). W Machines → pve → Edit route settings trasa `192.168.33.0/24` musi być zatwierdzona.
2. Admin → Access controls, dopisz tag i dostęp **tylko** do SSH serwera aplikacji (nie do całej podsieci):
   ```json
   "tagOwners": { "tag:ci": ["autogroup:admin"] },
   "grants": [{ "src": ["tag:ci"], "dst": ["192.168.33.113/32"], "ip": ["22"] }]
   ```
3. Settings → OAuth clients → nowy klient z zakresem **Auth Keys: write** i tagiem `tag:ci`.

Workflow dołącza do sieci jako tymczasowe urządzenie z `--accept-routes` (Linux bez tego nie korzysta z tras subnet routera) i znika po zakończeniu.

## 5. Sekrety repozytorium GitHub

Settings → Secrets and variables → Actions:

| Sekret | Wartość |
| --- | --- |
| `TS_OAUTH_CLIENT_ID`, `TS_OAUTH_SECRET` | klient OAuth z Tailscale |
| `DEPLOY_HOST` | `192.168.33.113` |
| `DEPLOY_USER` | `deploy` |
| `DEPLOY_SSH_KEY` | zawartość prywatnego `intermanage-deploy` |

Obrazy w GHCR są prywatne. Serwer loguje się do nich przy każdym wdrożeniu tokenem z workflow, więc nie trzeba trzymać na nim hasła do GitHuba.

## 6. Pierwsze uruchomienie

Push na `main` albo Actions → Deploy → Run workflow. Potem na serwerze:

```bash
cd /opt/intermanage
docker compose ps
docker compose logs backend | grep -i owner   # hasło właściciela, jeśli OWNER_PASSWORD było puste
```

## Na co dzień

- Każdy commit na `main` wdraża się sam, tylko gdy przejdą testy backendu oraz build i lint frontu.
- Ręczny restart: `docker compose up -d`. Bez `IMAGE_TAG` bierze `latest`, czyli ostatnie wdrożenie.
- Powrót do starszej wersji: `IMAGE_TAG=<sha commita> docker compose up -d`.
- Kopia bazy na LXC Postgresa: `pg_dump -U intermanage_prod intermanage_prod | gzip > backup-$(date +%F).sql.gz`, najlepiej z crona (albo backup całego LXC w Proxmoxie).
