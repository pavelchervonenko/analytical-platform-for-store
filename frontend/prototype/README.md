# Store Analytics HTML prototype

Static, dependency-free prototype for validating the cabinet information architecture,
responsive behavior and visual language before the React application is started.

This remains a visual reference only. The implemented UI contract is
[docs/current/frontend/README.md](../../docs/current/frontend/README.md); historical implementation
context is preserved in
[FRONTEND_HANDOFF.md](../../docs/history/handoffs/2026/08/FRONTEND_HANDOFF.md) and
[frontend-actions.md](../../docs/archive/legacy-contracts/frontend-actions.md).

Run it from the repository root:

```bash
python3 -m http.server 4173 --directory frontend/prototype
```

Then open <http://localhost:4173>.

The prototype contains fictional demonstration values only. It does not call backend APIs and
must not be used as production frontend code.
