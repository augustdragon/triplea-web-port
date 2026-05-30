/**
 * The Notes info tab: the map's game notes (the `<property name="notes">` HTML from the game XML),
 * pushed once by the server and rendered here. The notes are author-provided HTML (credits, rules
 * differences, play tips) — the same content the base game shows in its Notes tab.
 *
 * Theming: map authors usually wrap their notes in a light/white background with dark text (this
 * map uses `<div style="background-color:white;...">`), which clashes with our dark UI and left
 * light text on a white box. We scope a small style block to this container that strips author
 * background colors so the dark dock shows through, and render the text light — so the Notes tab
 * reads like every other info tab (light text on dark) instead of a jarring white card.
 *
 * The notes are trusted local content: they come from the game XML the user chose to load, served by
 * our own backend, so we render the HTML directly. (If we ever load untrusted/remote maps, sanitize.)
 */
export function NotesTab({ html }: { html: string }) {
  if (!html || html.trim() === "") {
    return <div style={{ color: "#778", padding: "8px 2px" }}>This game has no notes.</div>;
  }
  return (
    <div className="notes-content" style={{ maxWidth: 760 }}>
      <style>{NOTES_CSS}</style>
      {/* Trusted map content (see file header). */}
      <div dangerouslySetInnerHTML={{ __html: html }} />
    </div>
  );
}

// Scoped to `.notes-content` so it can't affect the rest of the app. Neutralizes the author's own
// background colors (the white box) and restyles links/rules/headings for the dark theme.
const NOTES_CSS = `
.notes-content { color: #dfe6ee; font-size: 13px; line-height: 1.6; }
.notes-content * { background-color: transparent !important; }
.notes-content b, .notes-content strong, .notes-content h1, .notes-content h2, .notes-content h3 {
  color: #ffffff;
}
.notes-content a { color: #7db4e6; }
.notes-content hr { border: none; border-top: 1px solid #3a4654; margin: 10px 0; }
.notes-content img { max-width: 100%; height: auto; }
.notes-content table, .notes-content td, .notes-content th { border-color: #3a4654; }
`;
