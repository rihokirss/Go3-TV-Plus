# Go3 catalogue contracts

Verified through the signed-in Sony Android 9 app on 2026-11-12. These are service implementation details, not a public Go3 API guarantee. Requests use the existing gateway authentication, profile headers and subscriber tier.

| Purpose | GET path below `/api/` | Shape |
| --- | --- | --- |
| Catalogue page | `products/vods?firstResult=…&maxResults=…&tier=…` | `items`, `meta.totalCount` |
| Detail | `products/vods/{id}` | Product object |
| Seasons | `products/vods/serials/{series}/seasons?tier=…` | Array with `id`, `number`, `title` |
| Episodes | `products/vods/serials/{series}/seasons/{season}/episodes?tier=…` | Array; order by numeric `episode` |
| Entitlements | `subscribers/products/available/till` | Array of `{id}` or `{id,till}` |

`VOD`, `SERIAL` and `EPISODE` are separate catalogue types. EPG IDs and EPG programmes are not catalogue playback models. Both films and episodes use the normal player configuration/playlist contract with `videoType=MOVIE`; `EPISODE` is not a valid ticket type. Normal Widevine licence handling is unchanged.

Entitlement requires presence in a successful entitlement snapshot and current availability. Neither `payable=false` nor presence in the catalogue proves entitlement. Missing snapshot means unknown; absence from a valid snapshot means locked. The entitlement response also contains many TV recording IDs: stream its parsing, persist only known catalogue IDs, and never allocate playback sessions to check rights.

Verified examples: film **Transformerid: Väljasuremine** (`3487393`), multi-season **Eesti narkomuulad** (`2694532`, first episodes `2694545`, `2694548`), and locked **Prometheus** (`1022149`). Film/episode playback obtained the regular configuration, playlist and Widevine licence; locked content returned `ITEM_NOT_PAID`. Sessions opened for verification were closed.

Room schema 6 stores catalogue metadata, FTS4 title/original-title index, seasons, rights, sync timestamps, profile watchlist and progress. Migration 4→5 removes `watch_now_matches` and preserves TV tables. A completed catalogue refresh may retire missing titles; an interrupted refresh cannot. Lightweight row revisions avoid reading every JSON blob after a single detail/episode update. Cold decoding exposes the first 120 titles before processing the rest. Reselecting the current catalogue section retains its composition, scroll state and focus requesters; focus is restored without rebuilding poster rows. Writes use batches of at most 64 products and DAO bulk operations; unchanged compact JSON skips repeated title parsing and FTS updates. Text/image regular expressions are compiled once.

The application owns one `TvPlayer`. Explicit VOD playback closes the previous session before requesting the next one. Metadata browsing uses a separate request mutex and never requests a ticket. Progress is profile scoped and synchronized with Go3; the watchlist remains local.

Resource limits: Coil memory 24 MB, disk 128 MB, two concurrent image response bodies; one catalogue request; compressed video target 24 MB with 15–30 second buffering limits. Catalogue preloading waits while an active video has less than five seconds buffered. The existing TCL `rtd6748` hardware synchronization setting remains in place.

## Profile viewing history

`GET subscribers/bookmarks/continue/watching` returns `items` (full history) and `continueWatchingItems` (the authoritative Continue membership). Wrappers contain `item`, integer-second `playTime`, `modifiedAt`, and optional `watchedAt`. Episodes carry their parent under `item.season.serial`. Only VOD/EPISODE entries are imported. Actual completion and Continue membership are retained separately.

`POST int/subscribers/bookmarks?type=WATCHED` accepts `{itemId, playTime, profile:{id,name,token}}`. The profile UID remains the request header and local partition key; the numeric server ID, name and signing token come from `subscribers/detail.profiles` and stay inside the application process. History requests never acquire a playback ticket.

Local progress and a profile/content-keyed outbox are committed in one Room transaction. Upload acknowledgments match both timestamp and position, preserving a newer seek made during an in-flight request. A full remote snapshot is pulled before queued writes and at most once per minute during active sync; network failures leave the queue intact. Merge uses modification times and a five-second tolerance for pending local changes, rather than the furthest position, so intentional rewinds synchronize. A separate remote revision prevents device clock skew from blocking subsequent server changes. Remote removals are applied only where no pending local change exists. Unchanged remote revisions and Continue/completion flags skip per-record transactions on repeated snapshots.

Migration 5→6 adds the durable outbox, completion/Continue/server revision fields, and a season-completeness flag. Existing local progress is queued for upload. A single episode imported from history never marks a season complete: next-episode resolution loads the actual ordered episode list. Profile changes cancel the old worker.

Sony round-trip verification creates one previously absent movie bookmark, tests uploads, a second-client change and a rewind, then deletes only that test record using `DELETE int/subscribers/bookmarks?type=WATCHED&itemId[]=…&token=…&profileId=…`. Existing account records are checked for preservation. Automated playback boundary tests suspend the debug sync worker and clean their artificial local progress before production installation.
