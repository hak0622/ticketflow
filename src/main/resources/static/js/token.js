(function () {
  const TOKEN_KEY = "access_token";

  function getToken() {
    return localStorage.getItem(TOKEN_KEY);
  }

  function clearToken() {
    localStorage.removeItem(TOKEN_KEY);
  }

  async function logout(redirectTo) {
    const response = await fetch("/logout", { method: "POST", redirect: "error" });
    if (response.status !== 204) throw new Error("Logout did not complete");
    clearToken();
    location.href = redirectTo || "/login";
  }

  function authHeaders(extraHeaders) {
    const token = getToken();
    return {
      ...(extraHeaders || {}),
      ...(token ? { Authorization: "Bearer " + token } : {}),
    };
  }

  async function apiFetch(url, options) {
    const opts = options || {};
    opts.headers = authHeaders(opts.headers);

    const res = await fetch(url, opts);

    if (res.status === 401) {
      clearToken();
      throw new Error("UNAUTHORIZED");
    }

    const contentType = res.headers.get("content-type") || "";
    if (contentType.includes("application/json")) return res.json();
    return res.text();
  }

  window.Token = { getToken, clearToken, logout, authHeaders, apiFetch };
})();
