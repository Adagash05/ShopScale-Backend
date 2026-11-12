# Dark mode

Status: implemented; the token logic is covered by `npm run test:theme` (4 pass). **Not visually checked in a browser**; screens with hard-coded colours may remain.
- Settings > Appearance: **Light, Dark, System** (`src/theme/ThemePicker.jsx`). System follows `prefers-color-scheme` live.
- Stored per device in localStorage (`shopscale.theme`); `index.html` has a pre-paint inline script that sets `data-theme` on `<html>` so there is no flash. A server-side per-user preference is **not** implemented.
- `src/styles.css` defines colour tokens on `:root` and overrides them for `[data-theme="dark"]`. The `--white` token acts as the surface colour, so existing components follow without edits. Charts (`src/charts.jsx`, `Insights.jsx`) read the tokens.
- Public marketing pages follow the same tokens.
- Follow-up: walk every screen in dark mode in a browser; check PDF/receipt print views stay light.
