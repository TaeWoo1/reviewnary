import type { Config } from "tailwindcss";

// Every colour is a Coral token from src/styles/tokens.css (Hallmark, theme dealt 2026-10-27), read through its
// oklch channels so the /10 tints keep working. The names stay the ones the screens already use, so the system
// changes in one place and no component had to be touched to take it.
const lch = (name: string) => `oklch(var(--lch-${name}) / <alpha-value>)`;

export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // The action colour is Coral's ink board, not a blue: the primary button is dark stock with its
        // label pressed in, and coral itself is kept for the one settled state (`paid`) and focus.
        brand: {
          DEFAULT: lch("board"),
          50: lch("paper-2"),
          600: lch("ink-2"),
          700: lch("board"),
          // Hover goes darker than rest, as before: the state that invites the press stays the readable one.
          800: lch("board-hover"),
        },
        surface: lch("cotton"),
        canvas: lch("paper"),
        // A tinted well one step under the paper: a pressed groove, an inset the eye must find on the canvas.
        well: lch("paper-3"),
        ink: lch("ink"),
        muted: lch("muted"),
        line: lch("rule"),
        paid: lch("paid"),
        good: lch("good"),
        warn: lch("warn"),
        bad: lch("bad"),
      },
      fontFamily: {
        sans: [
          "Pretendard Variable",
          "Pretendard",
          "-apple-system",
          "BlinkMacSystemFont",
          "Apple SD Gothic Neo",
          "Segoe UI",
          "Roboto",
          "sans-serif",
        ],
      },
      fontSize: {
        // Larger-than-default scale for 40-50+ operators.
        //
        // `xs` and `sm` were left at the Tailwind defaults (12px/1.33, 14px/1.43) while `base` was
        // raised to 17px — so the gap between a headline and the line under it grew instead of the
        // whole scale moving. Metadata is where this product says what it does NOT know, and 12px
        // is where that stops being read.
        // Reviewnary Product UI Redesign v1 (docs/frontend/reviewnary_design.md §1). `base` 16 is the floor for
        // 40-50대 eyes; the steps above it are tighter than before so a page title no longer competes
        // with the briefing sentence, and metadata (`sm`) is still a size that is read.
        xs: ["13px", "1.5"],
        sm: ["15px", "1.6"],
        base: ["16px", "1.6"],
        // Reviewnary Visual System v1 §2 — the ONE step above body, reserved for the assistant's own
        // prose. In a conversation the answer is the thing being read; every object under it steps
        // down to `base`/`sm`, so the sentence is the largest thing in its own turn without any
        // component special-casing it. 1.75 leading because Korean paragraphs at 1.6 crowd.
        prose: ["17px", "1.75"],
        lg: ["18px", "1.5"],
        xl: ["22px", "1.35"],
        /*
          <b>Two steps the product did not have</b> (product-owner decision, 2026-10-01 — Home visual
          target, final polish).

          <p>The target asks for a 28px page title over a 20px section title, and the scale ran
          18 → 22 → 26 → 32. Fitting the design to the nearest existing step was the wrong move twice over:
          at `xl`/`lg` the page title stood 4px above its own sections, and at `2xl`/`lg` the figures in the
          summary band were a step the rest of the product never spends. So the SCALE grows rather than the
          design bending — these two are named for the role they carry, because that is what makes them
          re-usable by the next screen brought onto the system instead of guessed at again.

          <p>`title` is the page's `h1` AND the one primary metric a screen may draw (both 28/700): on this
          product a Home's 「46건」 is a title — it is what the screen is about — and giving the two the same
          step is what stops a band of figures from out-shouting the name of the page it sits under.
          `section` is the `h2` every screen's regions take.
        */
        title: ["28px", "1.25"],
        section: ["20px", "1.4"],
        "2xl": ["26px", "1.25"],
        "3xl": ["32px", "1.2"],
      },
      // §4: 8px controls (Tailwind `lg`), 10px rows, 12px cards. The 16/20px of the previous shell read
      // as a consumer app; an operations workspace has edges.
      borderRadius: {
        xl: "10px",
        "2xl": "12px",
      },
      width: {
        // 240 (UI System v2, Home visual target 2026-10-01). 232 was the contract's own figure and the
        // target mockup measures 241 — one step on the 8px grid, and the rail now divides the 1600px
        // screen where the mockup divides it.
        sidebar: "240px",
      },
      maxWidth: {
        /*
          <b>1280, not 1120</b> (product-owner decision, 2026-10-01; `docs/frontend/reviewnary_design.md` §2
          amended in the same change).

          <p>Measured at 1600×1000: the rail takes 240, `main` is 1360 and its own `md:px-8` leaves
          1296 to spend — so a 1120 column ended at x=1392 and left 176px of the monitor carrying
          nothing. 1280 is the next 8px step that keeps a 16px tail of slack rather than running the
          content to the exact edge of the padding box.

          <p>It is one token on purpose. The three places that read it — the plain-page column
          (`AppShellV2`), the master-detail list column (`MasterDetail`) and the quiet composer dock
          (`ConversationWorkspace`) — are the three the audit (§5, §10.2) found disagreeing, and a
          Home-only width would restore exactly that: 오늘's list and 확인할 일's list are the same
          list at the same breakpoint. Measured blast radius: the plain pages, 오늘 and 확인할 일 widen;
          문의 and 리뷰 do NOT, because with the 440px pane open their column is already 856.
        */
        content: "1280px",
        // The conversation's reading column. 840px ran to ~52 Korean characters a line; 720 lands
        // near 45, which is where Korean prose stops needing the eye to travel back (§2).
        thread: "720px",
      },
      boxShadow: {
        card: "var(--shadow-card), var(--cast-stock)",
        // Reviewnary Visual System v1 §7 — elevation is not decoration here, it is the mark of the
        // ONE live element on the page. The conversation is paper and every object is set into it
        // with rules; the box the seller types in is the only thing that sits ON the paper.
        composer: "0 1px 2px rgba(25,31,40,0.04), 0 10px 28px rgba(25,31,40,0.07)",
        // The selected row of a list, as an inset bar in the accent. A selected row is not elevated
        // and takes no border — §4 allows no shadow on a resting surface — so this is the one
        // `boxShadow` the app surface spends on state rather than on floating.
        selected: "inset 3px 0 0 oklch(var(--lch-board))",
      },
    },
  },
  plugins: [],
} satisfies Config;
