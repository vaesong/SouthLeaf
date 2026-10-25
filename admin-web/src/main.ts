import { createApp } from "vue";
import { ElButton, ElInput } from "element-plus";
import "element-plus/es/components/button/style/css";
import "element-plus/es/components/input/style/css";
import App from "./App.vue";
import { AdminAuthApi } from "./auth/api";
import { createAuthStore, authStoreKey } from "./auth/store";
import { createAdminRouter } from "./router";
import "./style.css";

const auth = createAuthStore(new AdminAuthApi());
const router = createAdminRouter(auth);

createApp(App).provide(authStoreKey, auth).use(router).use(ElButton).use(ElInput).mount("#app");

window.addEventListener("focus", () => {
  if (auth.state.status === "authenticated") void auth.checkSession();
});

document.addEventListener("visibilitychange", () => {
  if (!document.hidden && auth.state.status === "authenticated") void auth.checkSession();
});
