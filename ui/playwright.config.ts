import { defineConfig } from "@playwright/test";

// E2E runs against the Vite dev server (proxies /api → :18190). The episodes spec stubs
// /api/episodes in the page, so no backend is needed. video:'on' records every test.
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,        // one dev server, recorded videos → serialize
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  timeout: 120_000,
  use: {
    baseURL: "http://localhost:5174",
    video: "on",
    viewport: { width: 1280, height: 800 },
    actionTimeout: 15_000,
  },
  webServer: {
    command: "npm run dev",
    url: "http://localhost:5174",
    // false: Playwright owns the Vite server. Reusing whatever is already on :5174 risks
    // silently testing a DIFFERENT app (and a false PASS); a stale occupant fails loudly instead.
    reuseExistingServer: false,
    timeout: 60_000,
  },
  projects: [
    // Runs first: loads "/" and waits for app text, forcing Vite to complete its first
    // compile before any recorded spec begins. video:'off' keeps its webm out of the results.
    { name: "warmup", testMatch: /warmup\.setup\.ts/, use: { video: "off", channel: "chrome" } },
    // The specs run after warmup finishes and inherit the top-level `use` (video, viewport, baseURL).
    { name: "chromium", dependencies: ["warmup"] },
  ],
});
