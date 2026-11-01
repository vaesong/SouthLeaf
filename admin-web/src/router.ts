import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import type { AuthStore } from "./auth/store";
import { safeReturnPath } from "./auth/navigation";

export { safeReturnPath } from "./auth/navigation";

export function createAdminRouter(auth: AuthStore) {
  const routes: RouteRecordRaw[] = [
    { path: "/login", name: "login", component: () => import("./views/LoginView.vue") },
    {
      path: "/",
      name: "today",
      component: () => import("./views/TodayView.vue"),
      meta: { requiresAuth: true, title: "今日工作台" },
    },
    {
      path: "/home-layout",
      name: "home-layout",
      component: () => import("./views/HomeLayoutView.vue"),
      meta: { requiresAuth: true, title: "首页布置" },
    },
    {
      path: "/about",
      name: "about",
      component: () => import("./views/AboutView.vue"),
      meta: { requiresAuth: true, title: "关于我们" },
    },
    {
      path: "/businesses",
      name: "businesses",
      component: () => import("./views/BusinessesView.vue"),
      meta: { requiresAuth: true, title: "业务与套餐" },
    },
    {
      path: "/works",
      name: "works",
      component: () => import("./views/WorksView.vue"),
      meta: { requiresAuth: true, title: "作品集" },
    },
    {
      path: "/works/:workId",
      name: "work-edit",
      component: () => import("./views/WorksView.vue"),
      meta: { requiresAuth: true, title: "作品集" },
    },
    {
      path: "/media",
      name: "media",
      component: () => import("./views/MediaView.vue"),
      meta: { requiresAuth: true, title: "图片库" },
    },
    {
      path: "/bookings",
      name: "bookings",
      component: () => import("./views/BookingsView.vue"),
      meta: { requiresAuth: true, title: "预约处理" },
    },
    {
      path: "/slots",
      name: "slots",
      component: () => import("./views/SlotsView.vue"),
      meta: { requiresAuth: true, title: "档期安排" },
    },
    {
      path: "/notifications",
      name: "notifications",
      component: () => import("./views/NotificationsView.vue"),
      meta: { requiresAuth: true, title: "消息通知" },
    },
    {
      path: "/releases",
      name: "releases",
      component: () => import("./views/ReleasesView.vue"),
      meta: { requiresAuth: true, title: "发布记录" },
    },
    { path: "/catalog", redirect: "/about" },
    { path: "/outbox", redirect: "/notifications" },
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
