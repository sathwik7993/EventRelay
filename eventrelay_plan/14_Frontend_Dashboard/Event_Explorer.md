# EventRelay — Event Explorer UI

This document details the design, filter sets, and JSON payload viewer interface implemented in the Event Explorer page of the EventRelay dashboard.

---

## 1. UI Layout: Event Explorer

The Event Explorer allows developers to search through historical event logs:

- **Filter Bar**: Inputs to filter by `Event ID`, `Event Type` (e.g., `user.*`), `Date Range` (Start/End datepickers), and `Delivery Status` (e.g., `DELIVERED`, `RETRYING`).
- **Interactive JSON Viewer**: Click on any event row to expand a full screen pane displaying:
  - Formatted JSON payload with syntax highlighting.
  - Interactive copy button.
  - **Delivery Attempt Timeline**: A vertical step list showing each attempt, its timestamp, duration, signature headers, and HTTP status code.

---

## 2. Performance Safeguards

- **Cursor-Based Pagination**: Tables do not use offsets. The UI calls cursor-based pagination APIs (`GET /api/v1/events?cursor=...`) to ensure fast database execution on millions of records.
- **Export Limit**: Users can export filtered event queries to CSV. Exports are restricted to a maximum of 5,000 records to prevent browser memory exhaustion.
