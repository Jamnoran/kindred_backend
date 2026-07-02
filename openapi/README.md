# OpenAPI contract

`kindred-api.json` is the exported OpenAPI spec — the contract the web repo
generates its typed client from (ARCHITECTURE.md §4, "Contract flow").

Regenerate it after changing any endpoint:

```sh
./gradlew generateOpenApiDocs
```

The task boots the API with the `openapi` Spring profile (in-memory H2, no
Redis/MySQL needed), fetches `/v3/api-docs`, and writes the result here. CI
fails if the committed spec is stale.

A running instance also serves the live spec at `/v3/api-docs` and interactive
docs at `/swagger-ui.html`.
