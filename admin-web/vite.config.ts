import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";

export default defineConfig(async ({ mode }) => {
  const plugins = [vue()];
  if (mode === "mock") {
    const { mockAdminApi } = await import("./mock/plugin");
    plugins.push(mockAdminApi());
  }
  return {
    plugins,
    base: "/",
    envPrefix: "VITE_PUBLIC_",
    server: {
      host: "0.0.0.0",
      strictPort: true,
      port: 5173,
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
  };
});
