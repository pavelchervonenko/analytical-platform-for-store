# Store Analytics HTML prototype

Static, dependency-free prototype for validating the cabinet information architecture,

Status on 2026-07-23: this remains a visual reference only. Implemented backend screens and
actions are documented in `docs/history/handoffs/2026/08/FRONTEND_HANDOFF.md` and `docs/archive/legacy-contracts/frontend-actions.md`.
responsive behavior and visual language before the React application is started.

Run it from the repository root:

```bash
python3 -m http.server 4173 --directory frontend/prototype
```

Then open <http://localhost:4173>.

The prototype contains fictional demonstration values only. It does not call backend APIs and
must not be used as production frontend code.
