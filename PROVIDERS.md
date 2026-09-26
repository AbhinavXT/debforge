# Debrid providers

DebForge talks to debrid services through one interface,
`data/provider/DebridProvider.kt`. The UI, the queue, and the chunked download
engine never see service-specific types.

```
DebridProvider
 ├─ info              name, where to get the token, empty-list hint
 ├─ validateToken()   sign-in check, doesn't save the token
 ├─ listFiles()       one page of ready files, flattened to DownloadItem
 ├─ resolveLink()     sourceRef → fresh direct URL (lazy + on expiry)
 └─ addCapabilities / add()   optional: magnets, .torrent files, links
```

Currently bound: **TorBox** (`provider/torbox`), **Real-Debrid** (`provider/realdebrid`).

## Adding a service

1. Add an entry to `domain/ProviderId.kt`. The name is persisted, so never rename an existing entry.
2. Create `data/provider/<name>/` with a Retrofit `XyzApi`, DTOs under `dto/`,
   and `XyzProvider : DebridProvider`.
   - Namespace item ids (for example `"xyz:123"`) so they can't collide with another service's ids.
   - Put whatever you need to re-mint a link into `sourceRef`.
   - Return `downloadUrl = null` if the listing has no direct URL. The engine then
     calls `resolveLink()` right before the first byte.
   - Throw `ProviderException` for API errors that come back with HTTP 200.
   - Optional: override `addCapabilities` + `add()` to accept magnets, `.torrent`
     files, or links. The Add dialog only offers what you declare.
3. In `di/ProviderModule.kt`, add a `provideXyzApi` (use `retrofitFor(...)`) and a
   `@Binds @IntoSet` line in `ProviderBindings`.

The Setup picker, the Settings switcher, the per-provider token storage, and link
refreshing all pick up the new service with no other changes.

## Behaviour notes

- Tokens are stored per provider, so switching services in Settings is instant.
- Every queued download records its provider. Its links always refresh through that
  provider, even after the user switches the active service.
- Rows queued before multi-provider support are migrated to `REAL_DEBRID`
  (Room v2 → v3). The legacy `rd_token` key is still read, so existing users stay signed in.

## Background downloads

The queue loop lives in `download/DownloadQueueRunner` and is hosted by:

- **Android 14 and later:** `DownloadJobService`, a user-initiated data transfer job.
  It isn't subject to Android 15's 6-hour daily limit for `dataSync` foreground services.
- **Android 11–13, or when the job can't be scheduled:** `DownloadService`, a `dataSync`
  foreground service. On Android 15 and later it handles `onTimeout()`: it saves progress,
  puts the file back in the queue, and notifies the user.

`DownloadScheduler.ensureRunning()` chooses the host. It runs after every enqueue and
every time the app opens, so paused-by-the-system downloads resume automatically.
