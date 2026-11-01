import { computed, inject, ref } from "vue";
import { onBeforeRouteLeave, type RouteLocationNormalized } from "vue-router";
import { authStoreKey } from "../auth/store";
import { catalogStoreKey, type CatalogStore } from "./catalogStore";

const EDITORS = new Set(["about", "home-layout", "businesses"]);

export function useCatalogAccess() {
  const auth = inject(authStoreKey);
  const store = inject(catalogStoreKey);
  if (!store) throw new Error("CatalogStore was not provided");
  const permissions = computed(() => auth?.state.identity?.permissions ?? []);
  const canEdit = computed(() => permissions.value.includes("CONTENT_EDIT"));
  const canPublish = computed(() => permissions.value.includes("CONTENT_PUBLISH"));
  const canRead = computed(() => permissions.value.includes("CONTENT_READ"));
  return { auth, store, canEdit, canPublish, canRead };
}

export function useCatalogLeaveGuard(store: CatalogStore) {
  const open = ref(false);
  let decide: (allowed: boolean) => void = () => undefined;

  onBeforeRouteLeave((to: RouteLocationNormalized) => {
    if (EDITORS.has(String(to.name)) || !store.dirty) return true;
    open.value = true;
    return new Promise<boolean>((resolve) => {
      decide = resolve;
    });
  });

  function stay() {
    open.value = false;
    decide(false);
  }

  function allow() {
    open.value = false;
    decide(true);
  }

  return { open, stay, allow };
}
