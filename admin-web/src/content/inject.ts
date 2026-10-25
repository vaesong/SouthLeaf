import { inject, type InjectionKey } from "vue";
import type { AdminContentApi } from "./api";

export const contentApiKey: InjectionKey<AdminContentApi> = Symbol("AdminContentApi");

export function requireContentApi(): AdminContentApi {
  const api = inject(contentApiKey);
  if (!api) throw new Error("AdminContentApi was not provided");
  return api;
}
