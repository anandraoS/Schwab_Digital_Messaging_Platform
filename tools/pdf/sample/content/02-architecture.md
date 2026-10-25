# Architecture Diagrams

Diagrams are authored as fenced blocks tagged `diagram`. The JSON may be inline on the fence line, in the block body, or in a file referenced as `diagram:file.json`.

## Layered Architecture (boxes-and-arrows)

```diagram {"type":"boxes-and-arrows","caption":"Layered platform architecture with lanes, labelled edges and a skip-lane edge","lanes":[{"id":"clients","label":"Clients","color":"#2E78C7","nodes":[{"id":"web","label":"Angular Console","sub":"operators"},{"id":"svc","label":"Upstream Services","sub":"senders"},{"id":"prov","label":"Provider Callbacks","sub":"webhooks"}]},{"id":"api","label":"API Layer","color":"#2E9D57","nodes":[{"id":"rest","label":"REST Controllers","sub":"validation, authz"},{"id":"cb","label":"Callback Endpoint","sub":"HMAC verified"}]},{"id":"core","label":"Core Services","color":"#E08A00","nodes":[{"id":"notif","label":"NotificationService"},{"id":"policy","label":"PolicyService","sub":"consent, quiet hours"},{"id":"route","label":"RoutingService"},{"id":"disp","label":"DispatchService"}]},{"id":"infra","label":"Infrastructure","color":"#8E44AD","nodes":[{"id":"db","label":"PostgreSQL","sub":"system of record"},{"id":"bus","label":"RabbitMQ","sub":"priority queues"},{"id":"redis","label":"Redis"},{"id":"mongo","label":"MongoDB","sub":"diagnostics"}]}],"edges":[{"from":"web","to":"rest","label":"HTTPS"},{"from":"svc","to":"rest","label":"submit"},{"from":"prov","to":"cb","label":"receipts"},{"from":"rest","to":"notif"},{"from":"cb","to":"disp","label":"events"},{"from":"notif","to":"policy"},{"from":"notif","to":"route"},{"from":"route","to":"disp","style":"dashed"},{"from":"notif","to":"db","label":"persist"},{"from":"disp","to":"bus","label":"publish","color":"#C62828"},{"from":"disp","to":"mongo","style":"dashed","label":"best effort"},{"from":"web","to":"redis","label":"rate limit","style":"dashed"}]}
```

## Flow Left to Right

```diagram
{"type":"boxes-and-arrows","direction":"LR","caption":"Left-to-right flow with three stages",
 "lanes":[
  {"label":"Ingest","color":"#2E78C7","nodes":[{"id":"a","label":"Submit API"},{"id":"b","label":"Bulk CSV Job"}]},
  {"label":"Decide","color":"#E08A00","nodes":[{"id":"c","label":"Policy and Routing","sub":"suppression, preferences, channel choice"}]},
  {"label":"Deliver","color":"#2E9D57","nodes":[{"id":"d","label":"Email Provider"},{"id":"e","label":"SMS Provider"},{"id":"f","label":"Push Provider"}]}],
 "edges":[{"from":"a","to":"c","label":"single"},{"from":"b","to":"c","label":"chunked"},{"from":"c","to":"d"},{"from":"c","to":"e"},{"from":"c","to":"f"}]}
```

## State Machine

```diagram:state.json
```

Text after the diagram continues normally. A second state machine laid out on a circle with a self transition:

```diagram {"type":"state","layout":"circle","caption":"Circle layout (circuit breaker)","states":[{"id":"CLOSED","initial":true,"kind":"ok"},{"id":"OPEN","kind":"error"},{"id":"HALF_OPEN","kind":"warn"}],"transitions":[{"from":"CLOSED","to":"OPEN","label":"failure rate above threshold"},{"from":"OPEN","to":"HALF_OPEN","label":"wait duration elapsed"},{"from":"HALF_OPEN","to":"CLOSED","label":"probes succeed"},{"from":"HALF_OPEN","to":"OPEN","label":"probe fails"},{"from":"CLOSED","to":"CLOSED","label":"success"}]}
```
