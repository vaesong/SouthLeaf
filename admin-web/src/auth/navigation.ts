/** Keep post-login navigation within the two S1 routes that actually exist. */
export function safeReturnPath(candidate: unknown): string {
  if (typeof candidate !== "string" || candidate.includes("\\")) return "/";
  if (candidate.startsWith("//") || candidate.includes(":") || candidate.includes("%")) return "/";
  if (candidate.includes("?") || candidate.includes("#")) return "/";
  return candidate === "/forbidden" ? "/forbidden" : "/";
}
