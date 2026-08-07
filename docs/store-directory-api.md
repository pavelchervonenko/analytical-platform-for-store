# Store directory API

Status: implemented and verified on 2026-07-23. This endpoint is the only source for the frontend
store switcher; action consequences are listed in `docs/frontend-actions.md`.

`GET /api/stores` returns the active stores available to the authenticated user.

- `ADMIN` receives every active store.
- `MANAGER` receives only active stores assigned through `user_store_access`.
- A session that still requires a password change is rejected with `403`.
- The response is ordered by store name case-insensitively and then by UUID.

Example response:

```json
[
  {
    "id": "00000000-0000-0000-0000-000000000001",
    "name": "Future Store",
    "address": "Ленинский проспект, 30",
    "timezone": "Europe/Kaliningrad",
    "businessDayStart": "00:00:00",
    "opensAt": "10:00:00",
    "closesAt": "21:00:00",
    "active": true
  }
]
```

`address` may be `null`. Time fields use ISO local-time strings. The endpoint never accepts a user
identifier or a requested store list from the client; scope is derived from the authenticated
principal and persisted access grants.
