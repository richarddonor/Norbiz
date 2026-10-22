# Company theme

Each company gets its own look, so users can tell at a glance which company they're working in. The backend
only **stores** the choices; the frontend (Norbiz-Web `src/theme/`) turns them into CSS.

## What a theme controls

| Field | Values | Effect |
|---|---|---|
| `preset` | `null` (default Norbiz blue), a frontend palette key (`NORBIZ`, `INDIGO`, `TEAL`, `EMERALD`, `OCHRE`, `CRIMSON`, `PLUM`, `GRAPHITE`), or `CUSTOM` | Brand colour. Neutral surfaces get a faint wash of its hue. |
| `color` | `#rrggbb`; required for `CUSTOM`, cleared otherwise | Custom brand colour |
| `radius` | `SHARP` / `SOFT` / `ROUND` | Corner radius of every `rounded-*` utility |
| `sidebar` | `LIGHT` / `TINTED` / `DARK` | Sidebar background |
| `surface` | `PLAIN` / `PAPER` / `GRAIN` | Background of record forms (`DocSheet`) |
| `motion` | `DOCUMENT` / `SLIDE` / `NONE` | How record tabs open and close |
| logo | PNG, JPEG or WEBP, at most 512 KB | Sidebar brand, favicon, sign-in page |

All of these are columns on `companies` (`Company.theme*`, `logoPath`, `themeUpdatedAt`). Edit them only through
`CompanyThemeService`.

## API

- `GET /companies/{id}/theme`: any member of the company.
- `PUT /companies/{id}/theme`: requires `MANAGE_COMPANY_THEME`.
- `POST /companies/{id}/logo` (multipart `file`) and `DELETE /companies/{id}/logo`: require `MANAGE_COMPANY_THEME`.
- `GET /company-logos/{companyId}/logo.{png|jpg|webp}`: **public**, because the sign-in page and favicon load it without a token.
  - Cached for a year. `logoUrl` carries `?v=<themeUpdatedAt>`, so a new upload busts the cache.
  - Only the exact `logo.<ext>` name under `{upload-dir}/{companyId}/branding/` is served.

Every call checks `CompanyAccess.assertCompanyAccess`.

Logos are checked by their magic bytes, not by filename or Content-Type. Only raster formats are accepted (no SVG), so the public URL can never serve script.

`AuthResponse.companies[]` and `MeResponse.companies[]` carry each company's `theme`, so the frontend gets the theme from login or `/auth/me` without an extra request.

## Frontend

- `applyTheme()` sets the brand palette as inline CSS variables on `<html>`. These include `--primary`, `--background`, `--border`, and `--brand-h` / `--brand-s` (hue and saturation, used by the sidebar styles).
  - It also sets `data-radius`, `data-sidebar`, `data-surface` and `data-motion`, which `index.css` keys off.
  - It sets the tab title (`<Company> · Norbiz`) and the favicon.
- `CompanyThemeSync` applies the active company's theme and remembers it in `localStorage` (`last_company_theme`). Signed out, the sign-in page shows that last theme.
- `src/theme/motion.ts` uses the Web Animations API on the workspace tab panel:
  - **Open** (`DOCUMENT`): the record is lifted out of a drawer and tilts flat.
  - **Close** (`DOCUMENT`): the record is folded into its tab, which then gives a small "drawer shut" bump. `WorkspaceProvider.closeTab` switches to the next tab at once and keeps the closing panel on top (`closingKeys`) until the animation ends.
  - "Close all", background tabs, `NONE` and the OS reduced-motion setting all skip the animation.
- Page: **Access Control → Company Theme** (`/company-theme`). It edits the *active* company and previews live while its tab is shown.
