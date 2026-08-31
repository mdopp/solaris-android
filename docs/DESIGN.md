# Solaris Companion — Design Guideline

Der visuelle Standard für App-Screens **und** Widgets, damit alles wie *ein*
Produkt wirkt. Neue UI/Widgets richten sich hiernach; Abweichungen sind bewusst
zu begründen. Spiegelt das Solaris-Web-Frontend.

> **Zwei Akzente, nach Nachbarschaft getrennt (#130).** Der App-Rumpf wird
> *neben* der Weboberflaeche gesehen (seit #115 oeffnet sie in der App), also
> folgt er ihr — Akzent **orange**. Launcher-Icon, App-Icon-Shortcuts, Widgets
> und Benachrichtigungen werden *neben fremden* Icons gesehen, nicht neben der
> Weboberflaeche; sie behalten das **Markenblau**. Der Markenblock (Wortmarke im
> Glow) ist dieselbe Marke wie das Icon und bleibt ebenfalls blau.

## Palette (`res/values/colors.xml`)
| Rolle | Token | Hex |
|---|---|---|
| Hintergrund | `solaris_bg` | `#070709` |
| Erhöht (Statusbar/Nav) | `solaris_bg_elevated` | `#0C0C0E` |
| **Akzent (Primär, In-App)** | `solaris_accent` | `#F97316` (Solaris-Orange) |
| Akzent gedrückt | `solaris_accent_dark` | `#EA580C` |
| Akzent-Tint (12 %) | `solaris_accent_faint` | `#1FF97316` |
| **Markenblau** (Icon/Shortcuts/Widgets/Glow) | `solaris_brand_blue` | `#3B82F6` |
| Markenblau gedrückt | `solaris_brand_blue_dark` | `#2563EB` |
| Text | `solaris_text` | `#F2F2F5` |
| Text gedämpft | `solaris_text_muted` | `#9AA0AE` |
| Text schwach | `solaris_text_faint` | `#6B7280` |
| Energie „liefert"/„verbunden" | `energy_supply` | `#66BB6A` |
| Energie „bezieht" | `energy_draw` | `#EF5350` |
| Batterie | `energy_battery` | `#7E9CFF` |

**Domänen-Akzente** (Geräte-Widgets, aktiver Zustand): Licht `#FFC107`,
Cover/Jalousie `#7E9CFF`, Schalter `#66BB6A`, Klima `#FF8A65`.

> Regel: **In-App-Akzent ist immer `solaris_accent`, Akzent auf dem
> Android-Startbildschirm immer `solaris_brand_blue`.** `#7E9CFF` ist die
> *Batterie/Cover*-Farbe — nicht als generischer Akzent verwenden.

## Tokens (`res/values/dimens.xml`)
- Radien: `radius_sm 12` (Felder/Chips/Kacheln) · `radius_md 16` (Buttons) · `radius_lg 20` (Cards).
- Abstände: `space_xs 8 · sm 12 · md 16 · lg 24 · xl 32`; Screen-Gutter 24dp.
- Typo: `text_hero 40 · headline 22 · title 17 · body 15 · label 13 · tile 12`.
- Markenblock: `brand_lockup_top · brand_figure_height · brand_rule_bottom ·
  hero_glow_size · text_hero` (Erstauftritt) und dieselben mit `_compact`
  (gekoppelt, #130) — `OnboardingHomeActivity.renderBrand` schaltet um.
- Text-Styles: `Text.Solaris.Hero/Headline/Title/Body/Label/Tile` (`res/values/themes.xml`).

## Widgets — allgemein
- Hintergrund immer **`@drawable/widget_bg`** (22dp, dezenter Vertikal-Verlauf, 1dp-Hairline).
- **RemoteViews-sicher:** nur `LinearLayout/FrameLayout/RelativeLayout/GridLayout`,
  `TextView/ImageView/ProgressBar/Button/ImageButton/ListView` — **nie** ein
  nacktes `<View>` (bricht RemoteViews → „Widget kann nicht geladen werden").
- Icons tintbar, aktiver Zustand = Domänen-Akzent, inaktiv = gedämpft.
- Größenadaptiv (tiny/small/wide/medium/large); kein großer leerer Block.

## Auslöser-Kacheln (Kamera, Sprechen, künftige „öffnet X"-Kacheln) — **einheitlich!**
Kacheln, die nur eine Aktion auslösen, folgen **einem** Muster:

1. **Aufbau:** vertikal, zentriert — **Icon oben, einzeiliges Label darunter**. Root
   `widget_bg`, Padding `space_sm` (12dp).
2. **Icon:** gefüllter **Markenblau-Kreis** (`tile_accent_circle`, #3B82F6) mit
   weißem Glyph — signalisiert „antippen". **Gleiche Größe** überall (Kreis **52dp**,
   Glyph-Padding 12dp), **zentriert**. Kein flaches getöntes Glyph, keine
   abweichende Farbe (`#7E9CFF` etc.).
3. **Label:** **einzeilig**, `Text.Solaris.Tile`-Größe (12–13sp) **bold**,
   `solaris_text`, `maxLines=1`. **Keine zweite/abgeschnittene Zeile** (kein
   „Kamera öffnen"-Untertitel, der zu „Kamer…" trunkiert).
4. **Konsistenz:** gleiche Icon-Größe, gleiche Position, gleiche Label-Zeilenzahl,
   gleiche Abstände über *alle* Auslöser-Kacheln.

> Kurz: Kamera und Sprechen sollen **identisch** aufgebaut sein — nur Icon + Wort
> unterscheiden sich. Ein gemeinsames Kreis-Drawable (`tile_accent_circle`) und
> ein gemeinsames Kachel-Layout verwenden.

## Geräte-Widgets (Licht/Schalter/Cover)
- **Steuertasten füllen die Breite** (gewichtsbasiert, `layout_weight`), damit bei
  **keiner** Größe ein Button abgeschnitten wird — **nie** feste dp-Breiten, die
  überlaufen (sonst wird z.B. der ▼-Button rechts geclippt).
- **1×1 (TINY):** **Name behalten** (einzeilig, oben, ellipsiert) + der **Toggle
  ist ein tippbares, zustandsgetöntes Domänen-Icon** (kein Button-Kasten, kein „○") —
  Tap = Primäraktion, Name-Tap = PWA. Zustandsbalken unten wie gehabt (#57).
- **Nicht mögliche Aktionen ausgrauen:** eine Cover am Anschlag → die unmögliche
  Richtung ist deaktiviert/gedämpft (offen ⇒ ▲ grau & inert, zu ⇒ ▼ grau & inert).
  Ein grauer Button löst nichts aus.
- Zustand als Balken/Prozent unten; Akzent = Domänenfarbe im aktiven Zustand.

## App-Screens
Siehe Home-Screen (`activity_home.xml`): Hero mit Wortmark „Solar[Figur]s" +
Akzent-Glow, Status-Pill, ein filled Primär, Ghost-Sekundäraktionen. Konsistente
Radien/Abstände/Typo aus den Tokens; runde Karten `card_solaris`, alle mit
demselben Innenabstand `card_padding`.

**Reihenfolge ist Priorität (#126).** Der ungekoppelte Zustand zeigt den
Markenauftritt — eine leere App muss sich vorstellen. Der **gekoppelte** Zustand
lässt ihn weg und beginnt mit der Statuszeile (volle Spaltenbreite wie alles
andere), darunter der **filled Primär in die Solaris-Oberfläche**, dann die
Dinge, die der Hub selbst besitzt. Was selten gebraucht wird, steht hinter einer
Aufklappzeile („Weitere Widgets") oder als stiller Link unten — **versteckt, nie
entfernt**. Karten sagen in **einer** Zeile, was etwas tut; das Warum steht hinter
einem Info-Symbol, nicht im Fließtext auf dem Bildschirm.

## Die Naht zur Weboberfläche (#127)
Seit #115 öffnet die Solaris-Weboberfläche **in** dieser App. Der native Rumpf
nähert sich ihr an, **nie umgekehrt** — sie ist das Gesicht des Produkts, wird
auch am Rechner gesehen und liegt in einem anderen Repo. Die beiden Werte, die
sie für sich selbst angibt (`theme_color` / `background_color`), stehen als
`solaris_surface_theme` / `solaris_surface_bg` in `colors.xml`; **Status- und
Navigationsleiste des Rumpfs und die der Trusted Web Activity kommen beide von
dort**, damit sich der Rahmen beim Wechsel nicht verfärbt. Ändert sich die
Weboberfläche, sind diese zwei Zeilen die ganze Änderung.
