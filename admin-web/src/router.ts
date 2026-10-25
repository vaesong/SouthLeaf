import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import type { AuthStore } from "./auth/store";
import { safeReturnPath } from "./auth/navigation";

export { safeReturnPath } from "./auth/navigation";

export function createAdminRouter(auth: AuthStore) {
  const routes: RouteRecordRaw[] = [
    { path: "/login", name: "login", component: () => import("./views/LoginView.vue") },
    {
      path: "/",
      name: "home",
      component: () => import("./views/HomeView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/catalog",
      name: "catalog",
      component: () => import("./views/CatalogView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/outbox",
      name: "outbox",
      component: () => import("./views/OutboxView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/bookings",
      name: "bookings",
      component: () => import("./views/BookingsView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/works",
      name: "works",
      component: () => import("./views/WorksView.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/works/:workId",
      name: "work-edit",
      component: () => import("./views/WorkEditView.vue"),
      meta: { requiresAuth: true },
    },
    { path: "/forbidden", name: "forbidden", component: () => import("./views/ForbiddenView.vue") },
    {
      path: "/:pathMatch(.*)*",
      name: "not-found",
      component: () => import("./views/NotFoundView.vue"),
    },
  ];
  const router = createRouter({ history: createWebHistory(), routes });

  router.beforeEach(async (to) => {
    if (auth.state.status === "unknown" || auth.state.status === "checking") {
      await auth.checkSession();
    }

    if (to.name === "login" && auth.state.status === "authenticated") {
      return safeReturnPath(to.query.returnTo);
    }
    if (to.meta.requiresAuth && auth.state.status !== "authenticated") {
      return {
        name: "login",
        query: {
          returnTo: safeReturnPath(to.fullPath),
          ...(auth.state.status === "unavailable" ? { status: "unavailable" } : {}),
        },
      };
    }
    return true;
  });

  return router;
}
