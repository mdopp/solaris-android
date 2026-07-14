# Solaris Companion — Design Guideline

Der visuelle Standard für App-Screens **und** Widgets, damit alles wie *ein*
Produkt wirkt. Neue UI/Widgets richten sich hiernach; Abweichungen sind bewusst
zu begründen. Spiegelt das Solaris-Web-Frontend + das ServiceBay-Blau.

## Palette (`res/values/colors.xml`)
| Rolle | Token | Hex |
|---|---|---|
| Hintergrund | `solaris_bg` | `#070709` |
| Erhöht (Statusbar/Nav) | `solaris_bg_elevated` | `#0C0C0E` |
| **Akzent (Primär)** | `solaris_accent` | `#3B82F6` (ServiceBay-Blau) |
| Akzent gedrückt | `solaris_accent_dark` | `#2563EB` |
| Akzent-Tint (12 %) | `solaris_accent_faint` | `#1F3B82F6` |
| Text | `solaris_text` | `#F2F2F5` |
| Text gedämpft | `solaris_text_muted` | `#9AA0AE` |
| Text schwach | `solaris_text_faint` | `#6B7280` |
| Energie „liefert"/„verbunden" | `energy_supply` | `#66BB6A` |
| Energie „bezieht" | `energy_draw` | `#EF5350` |
| Batterie | `energy_battery` | `#7E9CFF` |

**Domänen-Akzente** (Geräte-Widgets, aktiver Zustand): Licht `#FFC107`,
Cover/Jalousie `#7E9CFF`, Schalter `#66BB6A`, Klima `#FF8A65`.

> Regel: **UI-Akzent ist immer `solaris_accent` (#3B82F6).** `#7E9CFF` ist die
> *Batterie/Cover*-Farbe — nicht als generischer Akzent verwenden.

## Tokens (`res/values/dimens.xml`)
- Radien: `radius_sm 12` (Felder/Chips/Kacheln) · `radius_md 16` (Buttons) · `radius_lg 20` (Cards).
- Abstände: `space_xs 8 · sm 12 · md 16 · lg 24 · xl 32`; Screen-Gutter 24dp.
- Typo: `text_hero 40 · headline 22 · title 17 · body 15 · label 13 · tile 12`.
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
2. **Icon:** gefüllter **Solaris-Akzent-Kreis** (`solaris_accent`, #3B82F6) mit
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

## App-Screens
Siehe Home-Screen (`activity_home.xml`): Hero mit Wortmark „Solar[Figur]s" +
Akzent-Glow, Status-Pill, ein filled Primär, Ghost-Sekundäraktionen. Konsistente
Radien/Abstände/Typo aus den Tokens; runde Karten `card_solaris`.
