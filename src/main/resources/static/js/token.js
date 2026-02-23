(function () {
  const TOKEN_KEY = "access_token";

  function searchParam(key) {
    return new URLSearchParams(location.search).get(key);
  }

  function saveTokenFromUrl() {
    const token = searchParam("token");
    if (token) {
      localStorage.setItem(TOKEN_KEY, token);

      const url = new URL(location.href);
      url.searchParams.delete("token");
      history.replaceState({}, document.title, url.pathname + url.search);
    }
  }

  function getToken() {
    return localStorage.getItem(TOKEN_KEY);
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

    if (res.status === 401) throw new Error("UNAUTHORIZED");

    const contentType = res.headers.get("content-type") || "";
    if (contentType.includes("application/json")) return res.json();
    return res.text();
  }

  window.Token = { saveTokenFromUrl, getToken, apiFetch };
})();