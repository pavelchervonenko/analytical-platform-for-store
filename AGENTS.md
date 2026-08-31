# Project instructions

## Local frontend visual verification

- For every material frontend UI change, run `npm run visual:local` from `frontend/` before handing
  the work back.
- Visual checks are local-only. Never point `VISUAL_BASE_URL` at production, staging or another
  remote host.
- Use `VISUAL_ROUTES` to capture the routes affected by the change. Inspect the resulting desktop,
  tablet and mobile images in `frontend/visual-artifacts/` rather than treating a successful command
  as sufficient visual review.
- Keep credentials in environment variables only. Never commit them or rendered screenshots because
  screenshots can contain business data.
- If the local backend, credentials or required data are unavailable, report that limitation instead
  of claiming visual verification.
- Production deployment must use changes already verified against the local frontend and backend.

## Documentation impact

- Treat `docs/current/`, `docs/runbooks/`, `docs/security/` and accepted `docs/decisions/` as part
  of the implementation contract. Update the affected document in the same change as code,
  configuration, migration, API, formula, UI behaviour or operator procedure.
- Do not copy release, schema, image digest or production flag values outside
  `docs/current/project-state.md`. Update that file only after sanitized runtime verification and
  preserve the observation under `docs/history/`.
- Versioned files under `docs/prompts/` and `docs/schemas/` are runtime artifacts. Never rewrite or
  remove a published version as ordinary documentation cleanup.
- Register every documentation-like file in `docs/maintenance/documentation-inventory.tsv`.
  Deletions follow the two-stage `delete-candidate` and evidence/tombstone gate from the policy.
- Run `python3 -m unittest scripts/tests/test_documentation_check.py` and
  `python3 scripts/check-documentation.py --strict` for documentation changes and before release.
- Never place secrets, full environment dumps, provider payloads, personal data or business-data
  screenshots in documentation or evidence.
