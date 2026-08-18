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
