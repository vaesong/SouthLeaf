import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [vue()],
  base: "/",
  envPrefix: "VITE_PUBLIC_",
  server: {
    host: "0.0.0.0",
    strictPort: true,
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
  test: {
    environment: "jsdom",
    restoreMocks: true,
    clearMocks: true,
    include: ["src/**/*.{test,spec}.ts"],
  },
});
