import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The Episodes SPA talks to the :api Spring Boot control plane (dev port 18190).
// All UI calls are relative ("/api/..."), proxied here in dev so there is no CORS dance.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:18190",
        changeOrigin: true,
      },
    },
  },
});
