# kvdb
A key value db

## Features
- Simple http api
- KV kept In memory for fast reads
- Writes persisted to disk before sending a 200 back to the client
- Websocket listener to get notifications about updates and deletes
- API key header authentication supported
- Automatic backups
- Backups uploaded to S3 compatible storage
- DB data encrypted on disk
- Prometheus metrics exposed + db metrics
- Completely async server with a thread per core architecture
- Can be used as a library via jar dependency or as a service via docker container

## API

Server runs by default port 9090 - which can be overridden by setting the `port` env var

### Get key

```
Request: GET /kv/v1/:key

Response:
{
	"test": "foobar"
}
```

### Batch get
Keys should be csv of keys
keys=host:psql.xyz,host:redis.xyz
```
GET /kv/v1/batch?keys=?
{
	"host:psql.xyz": "10.10.10.1",
	"host:redis.xyz": "10.10.10.2"
}
```

### Get range
```kotlin
GET /kv/v1/range?from=?&to=?

{
  "test": "foobar",
  "test2": "foobar2"
}
```

### Put key

```kotlin
PUT /kv/v1/:key/:value

Response:
{
  "key": "value"
}
```

### Post key

```kotlin
POST /kv/v1/:key
Request:
{
  "value": "bar"
}

Response:
{
  "key": "bar"
}
```

### Delete key

```kotlin
Delete /kv/v1/:key

Response
{
  "key": "old value"
}
```

### DB Stats
```kotlin
GET /admin/db/stats
Response:
{
  "BYTES_WRITTEN": {
  "total": 159,
  "mean": 6.223166138930577,
  "expired": false
},
  "BYTES_READ": {
  "total": 0,
  "mean": 0.0,
  "expired": false
},
  "BYTES_MOVED_BY_GC": {
  "total": 0,
  "mean": 0.0,
  "expired": false
},
  "TRANSACTIONS": {
  "total": 4,
  "mean": 0.1170022668891561,
  "expired": false
},
  "READONLY_TRANSACTIONS": {
  "total": 1,
  "mean": 0.13736263736263737,
  "expired": false
},
  "ACTIVE_TRANSACTIONS": {
  "total": 0,
  "mean": 0.0,
  "expired": false
},
  "FLUSHED_TRANSACTIONS": {
  "total": 4,
  "mean": 0.11700221596336831,
  "expired": false
},
  "DISK_USAGE": {
  "total": 117,
  "mean": 6.549330494603103,
  "expired": false
},
  "UTILIZATION_PERCENT": {
  "total": 100,
  "mean": 13.766519823788546,
  "expired": false
},
  "key_count": 2,
  "listeners_count": 0
}
```

### Prometheus metrics

```kotlin
GET /admin/metrics
```

## Websocket listener

Connect to `/ws/v1/:apiKey` to get updates about puts and deletes

## Auth

If `enable_auth` environment variable is set then the value of `api_key` environment variable is 
used as the api key. The api key must be passed in as the `Authorization` header value with `Bearer ` as the prefix
 If `api_key` is blank then the default key is admin [`enable_auth` must still be true]
