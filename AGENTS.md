# AGENTS.md — ms-environment-monitoring

Spring Boot 4.0.5 / Java 17 microservice, domain **environment_monitoring**
(measurements, thresholds, analyses, alerts). Package `com.eduaircontrol.msmonitoring`,
port **3003**, PostgreSQL schema **`environment_monitoring`**
(DB `eduaircontrol_monitoring`). Governance:
`../docs-ea-control/09-microservices/services/05-environment-monitoring/`.

## Commands

```bash
./mvnw verify            # Testcontainers: Postgres + RabbitMQ (Docker needed)
./mvnw spring-boot:run   # needs PostgreSQL + RabbitMQ + POSTGRES_URL/USER/PASSWORD
docker compose up -d     # app :3003 + PostgreSQL :5435 + RabbitMQ :5673/:15673
```

`/health` is public; Swagger at `/swagger-ui.html`.

## Notes

- Ingest is **only** `POST /api/v1/measurements` (evaluates thresholds on write).
  The broker carries two other topologies: `sensor.installation.*` (outbox of
  ms-sensor-management) and `classroom.*` (outbox of ms-classroom-management).
- Layout: domain modules (`analysis`, `monitoring`) + `infrastructure` + `shared`.
- Liquibase on startup (`src/main/resources/db/changelog/`); `ddl-auto=validate`.
  Tests run on **Testcontainers** (Postgres, RabbitMQ) — Docker Engine 29 needs
  `docker.api.version` (set for surefire in `pom.xml`).
- Auth validates **RS256** access tokens against the ms-security JWKS. Writes: role `ADMIN`.
- `app.backfill.*` is a temporary migration path from the monolith with an expiry
  date; new history arrives via the broker.
- Use `spring-boot-starter-webmvc` (Spring Boot 4 name), not `-web`.
