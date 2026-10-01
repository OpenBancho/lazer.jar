# lazer.jar

> **Warning**  
> At the moment the server is in active development.

> Not affiliated with osu! or ppy Pty Ltd.  
> All rights belong to their respective owners.

`lazer.jar` is a lightweight, high-performance osu!(lazer) API server and backend designed to work seamlessly within the `bancho.jar` server ecosystem.

---

## Features

- [x] **OAuth2 Authentication** (`/oauth/token`, `/api/v2/oauth/token`)
- [x] **API v2 Endpoints**
  - [x] Current user info & stats (`/api/v2/me`)
  - [x] User profiles, stats, and rankings (`/api/v2/users/{id}`)
  - [x] User scores (Best, Recent, First) (`/api/v2/users/{id}/scores/{type}`)
  - [x] Beatmap sets & beatmaps lookup (`/api/v2/beatmapsets/{id}`)
- [x] **Solo Score Submission**
  - [x] Score token creation & submission (`/api/v2/beatmaps/{id}/solo/scores`)
  - [x] Leaderboards & beatmap scores (`/api/v2/beatmaps/{id}/scores`)
  - [x] PP Calculation & accuracy parsing
  - [x] Seamless compatibility with stable scores (Classic mod injection)
- [x] **Social & Chat**
  - [x] Friends & Relationships (`/api/v2/friends`)
  - [x] Chat channels & messaging
  - [x] Notifications & presence polling
- [x] **Ecosystem Integration**
  - [x] Shared MySQL database with `bancho.jar`
  - [x] Shared Redis cache / token management
  - [x] CORS enabled for web clients

---

## Requirements

- **Java 25** (or compatible toolchain)
- **MySQL 8.0+** / MariaDB (shared with `bancho.jar`)
- **Redis**

---

## Configuration

Copy `.env.example` to `.env` and fill in your configuration:

```bash
cp .env.example .env
```

### Environment Variables

```env
PORT=8000
LEVEL=DEV
DOMAIN=localhost:8000

DB_HOST=localhost
DB_USER=bancho
DB_PASS=changeme
DB_NAME=bancho
DB_PORT=3306
DB_TIMEZONE=UTC

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASS=
REDIS_DB=0

OSU_API_KEY=your_osu_api_key_here
DIRECT_SEARCH=https://osu.direct/api/search
```

---

## Building & Running

### Build Shadow JAR

```bash
./gradlew shadowJar
```

The compiled fat JAR will be generated in `build/libs/lazer-jar-1.0.0-all.jar`.

### Run

```bash
java -jar build/libs/lazer-jar-1.0.0-all.jar
```

Or via Gradle:

```bash
./gradlew run
```
