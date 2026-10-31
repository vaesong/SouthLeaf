import { readonly, reactive, type DeepReadonly, type InjectionKey } from "vue";
import { AdminApiError, type AdminIdentity, type AdminAuthApi } from "./api";

export type AuthStatus = "unknown" | "checking" | "authenticated" | "anonymous" | "unavailable";

interface MutableAuthState {
  status: AuthStatus;
  identity: AdminIdentity | null;
  csrfReady: boolean;
  action: "login" | "logout" | null;
  error: AdminApiError | null;
}

export interface AuthStore {
  readonly state: DeepReadonly<MutableAuthState>;
  checkSession(): Promise<void>;
  prepareCsrf(force?: boolean): Promise<boolean>;
  login(accountId: string, password: string): Promise<AdminIdentity>;
  logout(): Promise<void>;
}

function asApiError(error: unknown): AdminApiError {
  if (error instanceof AdminApiError) return error;
  return new AdminApiError(0, "NETWORK_ERROR", "无法连接管理服务，请检查连接后重试。");
}

export function createAuthStore(api: AdminAuthApi): AuthStore {
  const mutable = reactive<MutableAuthState>({
    status: "unknown",
    identity: null,
    csrfReady: false,
    action: null,
    error: null,
  });
  let sessionCheck: Promise<void> | undefined;

  async function checkSession(): Promise<void> {
    if (sessionCheck) return sessionCheck;
    const alreadyAuthenticated = mutable.status === "authenticated";
    if (!alreadyAuthenticated) mutable.status = "checking";
    mutable.error = null;
    sessionCheck = (async () => {
      try {
        mutable.identity = await api.getSession();
        mutable.status = "authenticated";
      } catch (error) {
        const failure = asApiError(error);
        mutable.error = failure;
        mutable.csrfReady = false;
        if (failure.status === 401) {
          mutable.identity = null;
          mutable.status = "anonymous";
        } else {
          mutable.identity = null;
          mutable.status = "unavailable";
        }
      } finally {
        sessionCheck = undefined;
      }
    })();
    return sessionCheck;
  }

  async function prepareCsrf(force = false): Promise<boolean> {
    if (force) mutable.csrfReady = false;
    try {
      await api.getCsrf(force);
      mutable.csrfReady = true;
      mutable.error = null;
      return true;
    } catch (error) {
      mutable.csrfReady = false;
      mutable.error = asApiError(error);
      return false;
    }
  }

  async function login(accountId: string, password: string): Promise<AdminIdentity> {
    mutable.action = "login";
    mutable.error = null;
    try {
      const identity = await api.login(accountId, password);
      mutable.identity = identity;
      mutable.status = "authenticated";
      mutable.csrfReady = false;
      // Do not undo a successful login if CSRF rotation is temporarily unavailable.
      await prepareCsrf();
      return identity;
    } catch (error) {
      const failure = asApiError(error);
      if (failure.status === 0) {
        await checkSession();
        if (mutable.status === "authenticated" && mutable.identity) return mutable.identity;
        if (mutable.status === "unavailable") {
          const unconfirmed = new AdminApiError(
            503,
            "LOGIN_RESULT_UNCONFIRMED",
            "登录结果暂时无法确认，请检查服务状态后重试。",
            failure.requestId,
          );
          mutable.error = unconfirmed;
          throw unconfirmed;
        }
      }
      if (failure.status === 401) {
        mutable.identity = null;
        mutable.status = "anonymous";
      }
      mutable.error = failure;
      throw failure;
    } finally {
      mutable.action = null;
    }
  }

  async function logout(): Promise<void> {
    mutable.action = "logout";
    mutable.error = null;
    try {
      if (!mutable.csrfReady && !(await prepareCsrf(true))) {
        throw mutable.error ?? new AdminApiError(503, "CSRF_UNAVAILABLE", "安全初始化尚未完成。");
      }
      await api.logout();
      mutable.identity = null;
      mutable.status = "anonymous";
      mutable.csrfReady = false;
      mutable.error = null;
    } catch (error) {
      const failure = asApiError(error);
      mutable.error = failure;
      if (failure.status === 403) mutable.csrfReady = false;
      // Failed revocation is not a successful logout; keep the current identity visible.
      if (failure.status === 401) {
        mutable.identity = null;
        mutable.status = "anonymous";
        mutable.csrfReady = false;
      }
      throw failure;
    } finally {
      mutable.action = null;
    }
  }

  return {
    state: readonly(mutable),
    checkSession,
    prepareCsrf,
    login,
    logout,
  };
}

export const authStoreKey: InjectionKey<AuthStore> = Symbol("admin-auth-store");
