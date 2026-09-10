# Scouting: adding a "Pancakeify Preferences" row to the account side drawer

Target: the drawer in the screenshot (Add account / Your Premium / Listening stats /
Recents / Your Updates / **Settings and privacy**). Verdict: **feasible**, moderate effort,
version-fragile unless we resolve obfuscated names dynamically (plan below).

## What the menu is

- The rows are **native** (not WebView), from package `com.spotify.sidedrawer.*`.
- Labels are arsc string resources named `string/sidedrawer_link_*`:
  | res id | name | label |
  |--------|------|-------|
  | 0x7f1325a7 | `sidedrawer_link_settings` | Settings and privacy |
  | 0x7f1325a6 | `sidedrawer_link_recents` | Recents |
  | 0x7f1325a8 | `sidedrawer_link_taste_profile` | (Listening stats) |
  | 0x7f1325a4/a5 | `sidedrawer_link_business_information*` | |

## The item model (obfuscated, v9.1.80.2221)

- Each row is a `Lp/r301;`. Constructor:
  ```
  r301(<init>(v8u category, Integer titleResId, String icon, e801 ?, q301 click, u301 ?, int ?))
  ```
- The settings row is built in `Lp/yoc;` (an R8-merged lambda; `invoke()` has a `packed-switch`,
  one branch per row). Settings branch: `new r301(k2u.c, Integer(0x7f1325a7), jka1.W0.a /*icon*/,
  …, new q301(new q5g0(t5g0), …), …)`. The default branch returns `pte.x1(provider.get())` —
  the assembled `List` of rows.
- Click mechanism: `q301(av71 action, tpz)`; `q5g0 implements av71`, `av71.b() : ct71` returns
  a **navigation command** (`ct71`). So a row's click = produce a `ct71` destination.

## Injection plan (robust, version-tolerant)

Do **not** hardcode `r301`/`yoc`/`q301` (they rename every Spotify build). Anchor on the stable
resource id instead:

1. Hook the drawer list provider (the method returning `List<r301>` — reachable via `yoc.invoke`
   default branch, or its consumer). Pine `after` hook.
2. In the returned list, find the item whose `titleResId == R.string.sidedrawer_link_settings`
   (resolve that id at runtime via `ctx.getResources().getIdentifier("sidedrawer_link_settings",
   "string","com.spotify.music")` — stable across versions).
3. **Clone that item** (shallow copy of the `r301`), then swap:
   - title → our "Pancakeify Preferences" string (add one arsc string in the patcher, or overlay),
   - click action (`av71`) → our own impl that opens `PancakePrefsActivity` (either return a
     Spotify `ct71` we build, or just `context.startActivity(our)` and return a no-op).
4. Insert the clone right after the settings item; return the modified list.

Cloning sidesteps constructing `r301`/`q301` from scratch and keeps us off the obfuscated
constructor signatures — only the click action must be supplied.

## Open questions to resolve when implementing

- Exact method to hook that yields the mutable `List` (confirm `yoc` default branch vs a consumer
  that copies to an immutable list — if immutable, hook one level up).
- Cheapest way to give the row our title (add `string/pancakeify_prefs` to arsc vs. post-process
  the bound TextView).
- Whether to reuse a Spotify `ct71` (open an in-app destination) or launch our own Activity.
