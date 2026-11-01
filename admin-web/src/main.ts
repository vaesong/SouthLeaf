import { createApp } from "vue";
import App from "./App.vue";
import { AdminAuthApi } from "./auth/api";
import { createAuthStore, authStoreKey } from "./auth/store";
import { AdminContentApi } from "./content/api";
import { createCatalogStore, catalogStoreKey } from "./content/catalogStore";
import { contentApiKey } from "./content/inject";
import { createAdminRouter } from "./router";
import "./style.css";

const auth = createAuthStore(new AdminAuthApi());
const contentApi = new AdminContentApi();
const router = createAdminRouter(auth);

createApp(App)
  .provide(authStoreKey, auth)
  .provide(contentApiKey, contentApi)
  .provide(catalogStoreKey, createCatalogStore(contentApi))
  .use(router)
  .mount("#app");

window.addEventListener("focus", () => {
  if (auth.state.status === "authenticated") void auth.checkSession();
});

document.addEventListener("visibilitychange", () => {
  if (!document.hidden && auth.state.status === "authenticated") void auth.checkSession();
});
