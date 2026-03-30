// Copyright (c) 2026 @Natfii. All rights reserved.

/** Filter callback signature: category, source, search text. */
export type FilterCallback = (
  category: string | null,
  source: string | null,
  search: string,
) => void;

/** View mode toggle callback. */
export type ViewToggleCallback = (mode: "graph" | "table") => void;

/**
 * Initialize the filter bar at the top of the app.
 *
 * Replaces the existing filter-bar content with category/source dropdowns,
 * a search input, and the graph/table toggle button.
 *
 * @param onFilter - Called when any filter value changes.
 * @param onViewToggle - Called when the view mode toggle is clicked.
 * @param initialView - The initial view mode.
 */
export function initFilterBar(
  onFilter: FilterCallback,
  onViewToggle: ViewToggleCallback,
  initialView: "graph" | "table",
): void {
  const bar = document.querySelector(".filter-bar");
  if (!bar) return;

  /** Clear existing content. */
  bar.textContent = "";

  /** Title. */
  const titleEl = document.createElement("span");
  titleEl.textContent = "Memory Brain";
  titleEl.style.cssText = "font-weight: 600; margin-right: auto;";
  bar.appendChild(titleEl);

  /** Category dropdown. */
  const categorySelect = createSelect(
    "filter-category",
    "Filter by category",
    [
      { value: "", label: "All Categories" },
      { value: "core", label: "Core" },
      { value: "daily", label: "Daily" },
      { value: "conversation", label: "Conversation" },
    ],
  );
  bar.appendChild(categorySelect);

  /** Source dropdown. */
  const sourceSelect = createSelect(
    "filter-source",
    "Filter by source",
    [
      { value: "", label: "All Sources" },
      { value: "heuristic", label: "Heuristic" },
      { value: "llm", label: "LLM" },
      { value: "user", label: "User" },
      { value: "agent", label: "Agent" },
    ],
  );
  bar.appendChild(sourceSelect);

  /** Search input. */
  const searchInput = document.createElement("input");
  searchInput.type = "search";
  searchInput.id = "filter-search";
  searchInput.placeholder = "Search memories...";
  searchInput.setAttribute("aria-label", "Search memories");
  searchInput.style.cssText = `
    padding: 6px 12px;
    border: 1px solid var(--border-subtle);
    border-radius: 8px;
    background: transparent;
    color: var(--text-primary);
    font-size: 13px;
    min-height: 48px;
    min-width: 120px;
    outline: none;
  `;
  bar.appendChild(searchInput);

  /** View toggle button. */
  const toggleBtn = document.createElement("button");
  toggleBtn.id = "view-toggle";
  toggleBtn.className = "close-btn";
  toggleBtn.setAttribute("aria-label", "Toggle view mode");
  toggleBtn.style.cssText = "font-size: 14px; min-width: auto; padding: 6px 12px;";
  let currentView = initialView;
  toggleBtn.textContent = currentView === "graph" ? "Show as Table" : "Show as Graph";
  bar.appendChild(toggleBtn);

  /** Debounce timer for search input. */
  let searchTimer: ReturnType<typeof setTimeout> | null = null;

  function emitFilter(): void {
    const cat = (categorySelect as HTMLSelectElement).value || null;
    const src = (sourceSelect as HTMLSelectElement).value || null;
    const text = searchInput.value.trim();
    onFilter(cat, src, text);
  }

  categorySelect.addEventListener("change", emitFilter);
  sourceSelect.addEventListener("change", emitFilter);
  searchInput.addEventListener("input", () => {
    if (searchTimer != null) clearTimeout(searchTimer);
    searchTimer = setTimeout(emitFilter, 300);
  });

  toggleBtn.addEventListener("click", () => {
    currentView = currentView === "graph" ? "table" : "graph";
    toggleBtn.textContent =
      currentView === "graph" ? "Show as Table" : "Show as Graph";
    localStorage.setItem("brain-view-mode", currentView);
    onViewToggle(currentView);
  });
}

/** Create a styled <select> element with the given options. */
function createSelect(
  id: string,
  ariaLabel: string,
  options: { value: string; label: string }[],
): HTMLSelectElement {
  const select = document.createElement("select");
  select.id = id;
  select.setAttribute("aria-label", ariaLabel);
  select.style.cssText = `
    padding: 6px 12px;
    border: 1px solid var(--border-subtle);
    border-radius: 8px;
    background: transparent;
    color: var(--text-primary);
    font-size: 13px;
    min-height: 48px;
    outline: none;
    cursor: pointer;
  `;

  for (const opt of options) {
    const optEl = document.createElement("option");
    optEl.value = opt.value;
    optEl.textContent = opt.label;
    select.appendChild(optEl);
  }

  return select;
}
