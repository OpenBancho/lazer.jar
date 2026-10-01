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
- [x] **Beatmap Mirror & Direct**
  - [x] Beatmap listing search (`/api/v2/beatmapsets/search`) with status & category mapping
  - [x] Direct beatmap download redirect (`/api/v2/beatmapsets/{id}/download`, `/d/{id}`)
  - [x] Fallback to local database for search and metadata lookup
- [x] **Ecosystem Integration**
  - [x] Shared MySQL database with `bancho.jar`
  - [x] Shared Redis cache / token management
  - [x] CORS enabled for web clients

---

## 📋 Roadmap & TODO

### 1. Real-time SignalR Hubs (WebSockets)
- [ ] **SignalR Metadata Hub** (`/signalr/metadata`)
  - [ ] Real-time online user counter & presence
  - [ ] Live user status updates (Playing, Editing, Spectating)
  - [ ] Global server announcements broadcast
- [ ] **SignalR Spectator Hub** (`/signalr/spectator`)
  - [ ] Live gameplay frame streaming (`FrameDataBundle`)
  - [ ] Spectator watching & broadcasting sessions
  - [ ] Spectator chat channel linking
- [ ] **SignalR Multiplayer Hub** (`/signalr/multiplayer`)
  - [ ] Real-time room state synchronization (host, room settings, queue)
  - [ ] Head-to-Head & Team vs Team matchmaking
  - [ ] Match start countdowns & real-time load progress tracking
  - [ ] Match end results synchronization
- [ ] **SignalR Chat Hub** (`/signalr/chat`)
  - [ ] Instant WebSocket message delivery (replacing polling)
  - [ ] Typing indicators and presence in channels

---

### 2. Multiplayer & Playlists REST API
- [ ] **Room Management** (`/api/v2/rooms`)
  - [ ] Room listing with filtering (Open, In-Progress, Ended)
  - [ ] Room creation, password protection, and privacy settings
  - [ ] Joining & leaving rooms via REST endpoints
- [ ] **Playlists & Matchmaking**
  - [ ] Playlist queue management (adding/removing beatmaps)
  - [ ] Multi-beatmap playlist score aggregation
  - [ ] Room history & match score storage

---

### 3. Replay Storage & Scoring
- [ ] **Replay File Serving** (`/api/v2/scores/{score_id}/download`)
  - [ ] Storing raw `.osr` / `.score` files on disk / S3-compatible storage
  - [ ] Streaming replay files to client for leaderboard replay viewing
- [ ] **Ruleset Simulation Expansion**
  - [x] osu!standard replay simulation & hit validation
  - [ ] osu!taiko replay parser & simulation
  - [ ] osu!catch replay parser & simulation
  - [ ] osu!mania replay parser & simulation
- [ ] **Custom Mod Modifiers**
  - [ ] Difficulty Adjust (CS/AR/OD/HP custom overrides)
  - [ ] Rate Adjust (0.5x - 2.0x playback speed multiplier handling in PP)

---

### 4. User Profiles & Social Expansion
- [ ] **Profile Customization**
  - [ ] User custom profile banners & bio page (`/api/v2/users/{id}/extra`)
  - [ ] Badges & title management
  - [ ] User statistics history graphs (rank history, playcount history)
- [ ] **Comments System** (`/api/v2/comments`)
  - [ ] Beatmapset & score comments posting
  - [ ] Comment upvotes, downvotes, and pinned comments
- [ ] **Beatmap Discussions & Mapping**
  - [ ] Mapping modding discussions (`/api/v2/beatmapsets/discussions`)
  - [ ] Kudosu balance transactions & awarding

---

### 5. Tournaments & Client Verification
- [ ] **Client Security & Anticheat Token**
  - [ ] Hardware ID / client verification integration (aligned with `ClientVerificationHandler`)
  - [ ] Client version checking & enforcement (`/api/v2/updates`)
- [ ] **Tournament Infrastructure** (`/api/v2/tournaments`)
  - [ ] Tournament client data streaming
  - [ ] Bracket management endpoints

---

### 6. Beatmap Packs & Daily Challenges
- [ ] **Beatmap Packs** (`/api/v2/beatmaps/packs`)
  - [ ] Pack categories (Theme, Standard, Chart, Spotlight)
  - [ ] Direct pack archive downloading
- [ ] **Daily Challenge**
  - [ ] Daily featured beatmap selection
  - [ ] Daily challenge leaderboards & special streaks

---

### 7. Documentation & Platform Services
- [ ] **Changelog & News**
  - [ ] News articles listing & markdown rendering (`/api/v2/news`)
  - [ ] Server build changelog stream (`/api/v2/changelog`)
- [ ] **Wiki**
  - [ ] Server rules, FAQ, and tutorials (`/api/v2/wiki`)

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
