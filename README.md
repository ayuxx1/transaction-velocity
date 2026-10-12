# Transaction Velocity API

Spring Boot service for per-account rolling-window transaction velocity checks.
It uses a Redis sorted set and an atomic Lua script so concurrent requests are
counted consistently. The service uses its own clock; clients cannot backdate
transactions to bypass a velocity rule.

## Requirements

- Docker Desktop with Docker Compose
- Java 17 and Maven 3.9+ for running outside Docker

## Start locally

Create a local environment file and generate strong secrets in PowerShell:

```powershell
Copy-Item .env.example .env
$apiKey = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
$redisPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(36))
$lines = Get-Content .env | ForEach-Object {
  if ($_ -like "APP_API_KEY=*") { "APP_API_KEY=$apiKey" }
  elseif ($_ -like "REDIS_PASSWORD=*") { "REDIS_PASSWORD=$redisPassword" }
  else { $_ }
}
Set-Content -Encoding Ascii .env $lines
docker compose up --build -d
docker compose ps
```

The app and Redis start as containers. Redis is not published to the host; the
API is bound to `127.0.0.1:8080`. `.env` is ignored by Git. Keep production
secrets in a secrets manager, use TLS to managed Redis, and rotate credentials.

Check readiness:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
```

## Evaluate a transaction

Read the generated API key from `.env`, then send a request:

```powershell
$apiKey = (Select-String '^APP_API_KEY=' .env).Line.Substring('APP_API_KEY='.Length)
$body = @{
  transactionId = "txn-001"
  accountId     = "acct-001"
  amount        = 25.50
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/transactions/evaluate" `
  -Headers @{ "X-API-Key" = $apiKey } `
  -ContentType "application/json" `
  -Body $body
```

The default policy allows five transactions per account per rolling 60 seconds.
The sixth response is HTTP `429` and includes the same JSON result shape as
successful requests. Repeating the same transaction ID and details within the
idempotency retention period does not increment the count; reusing that ID with
different details returns HTTP `409`.

Configure the policy with `VELOCITY_WINDOW_SECONDS` and
`VELOCITY_MAX_TRANSACTIONS`. Window length is limited to 1–86,400 seconds.
Account and transaction IDs must start with an ASCII letter or digit and may
contain letters, digits, `.`, `_`, or `-` (up to 64 characters). Amounts must be
positive and have at most two decimal places.

## Stop

```powershell
docker compose down
```

Redis data is stored in a named Docker volume and survives container restarts.
To remove local development data too, run `docker compose down -v`.

## Tests and CI

```powershell
.\mvnw.cmd --batch-mode verify
```

The integration tests use Testcontainers and require Docker. GitHub Actions
runs the tests and builds the production container image on pushes and pull
requests.

## Production deployment notes

The included Compose file is for local development, not a highly available
production deployment. For production, deploy multiple app replicas behind a
TLS-terminating load balancer and use a managed Redis service configured for
high availability, TLS, authentication, persistence, backups, and capacity
alerts. Configure secrets through the platform's secret manager. The API key
is a shared service credential; use an API gateway or identity provider for
per-caller authorization, key rotation, and audit requirements. This service
does not persist a transaction ledger and is not a complete fraud decision
engine.
