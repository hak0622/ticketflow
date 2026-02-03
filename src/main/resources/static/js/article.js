function parseJwt(token) {
    try {
        const base64Url = token.split('.')[1];
        if (!base64Url) return null;

        const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
        const jsonPayload = decodeURIComponent(
            atob(base64)
                .split('')
                .map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
                .join('')
        );
        return JSON.parse(jsonPayload);
    } catch (e) {
        console.error("JWT 파싱 실패:", e);
        return null;
    }
}

function showButtonsIfAuthor() {
    const authorEmailInput = document.getElementById('author-email');
    const modifyBtn = document.getElementById('modify-btn');
    const deleteBtn = document.getElementById('delete-btn');

    if (!authorEmailInput || !modifyBtn || !deleteBtn) return;

    const token = localStorage.getItem('access_token');
    if (!token) return;

    const payload = parseJwt(token);
    if (!payload) return;

    const myEmail = payload.sub || payload.email;
    const authorEmail = authorEmailInput.value;

    if (!myEmail || !authorEmail) return;

    if (myEmail === authorEmail) {
        modifyBtn.style.display = 'inline-block';
        deleteBtn.style.display = 'inline-block';
    }
}

document.addEventListener('DOMContentLoaded', () => {
    showButtonsIfAuthor();
});

const deleteButton = document.getElementById('delete-btn');

if (deleteButton) {
    deleteButton.addEventListener('click', event => {
        let id = document.getElementById('article-id').value;

        function success() {
            alert('삭제가 완료되었습니다.');
            location.replace('/articles');
        }

        function fail() {
            alert('삭제 실패했습니다.');
            location.replace('/articles');
        }

        httpRequest('DELETE', `/api/articles/${id}`, null, success, fail);
    });
}

const modifyButton = document.getElementById('modify-btn');

if (modifyButton) {
    modifyButton.addEventListener('click', event => {
        let params = new URLSearchParams(location.search);
        let id = params.get('id');

        body = JSON.stringify({
            title: document.getElementById('title').value,
            content: document.getElementById('content').value
        });

        function success() {
            alert('수정 완료되었습니다.');
            location.replace(`/articles/${id}`);
        }

        function fail() {
            alert('수정 실패했습니다.');
            location.replace(`/articles/${id}`);
        }

        httpRequest('PUT', `/api/articles/${id}`, body, success, fail);
    });
}

const createButton = document.getElementById('create-btn');

if (createButton) {
    createButton.addEventListener('click', event => {
        body = JSON.stringify({
            title: document.getElementById('title').value,
            content: document.getElementById('content').value
        });

        function success() {
            alert('등록 완료되었습니다.');
            location.replace('/articles');
        }

        function fail() {
            alert('등록 실패했습니다.');
            location.replace('/articles');
        }

        httpRequest('POST', '/api/articles', body, success, fail);
    });
}

function getCookie(key) {
    var result = null;
    var cookie = document.cookie.split(';');

    cookie.some(function (item) {
        item = item.replace(' ', '');

        var dic = item.split('=');

        if (key === dic[0]) {
            result = dic[1];
            return true;
        }
    });

    return result;
}

function httpRequest(method, url, body, success, fail) {
    fetch(url, {
        method: method,
        headers: {
            Authorization: 'Bearer ' + localStorage.getItem('access_token'),
            'Content-Type': 'application/json',
        },
        body: body,
    }).then(response => {
        if (response.status === 200 || response.status === 201) {
            return success();
        }

        const refresh_token = getCookie('refresh_token');

        if (response.status === 401 && refresh_token) {
            fetch('/api/token', {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer ' + localStorage.getItem('access_token'),
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    refreshToken: getCookie('refresh_token'),
                }),
            })
                .then(res => {
                    if (res.ok) {
                        return res.json();
                    }
                })
                .then(result => {
                    localStorage.setItem('access_token', result.accessToken);

                    showButtonsIfAuthor();

                    httpRequest(method, url, body, success, fail);
                })
                .catch(error => fail());
        } else {
            return fail();
        }
    });
}

// 좋아요 토글 (JSON 응답용 요청 함수)
function httpRequestJson(method, url, body, success, fail) {
    const options = {
        method: method,
        headers: {
            Authorization: 'Bearer ' + localStorage.getItem('access_token'),
            'Content-Type': 'application/json',
        }
    };

    // body가 있을 때만 넣기 (GET에서는 넣지 않음)
    if (body != null) {
        options.body = body;
    }

    fetch(url, options).then(response => {
        if (response.status === 200 || response.status === 201) {
            return response.json().then(data => success(data));
        }

        const refresh_token = getCookie('refresh_token');

        if (response.status === 401 && refresh_token) {
            fetch('/api/token', {
                method: 'POST',
                headers: {
                    Authorization: 'Bearer ' + localStorage.getItem('access_token'),
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    refreshToken: getCookie('refresh_token'),
                }),
            })
                .then(res => {
                    if (!res.ok) throw new Error("token refresh fail");
                    return res.json();
                })
                .then(result => {
                    localStorage.setItem('access_token', result.accessToken);
                    httpRequestJson(method, url, body, success, fail);
                })
                .catch(() => fail());
        } else {
            fail();
        }
    }).catch(() => fail());
}

document.addEventListener('DOMContentLoaded', () => {
    const likeButton = document.getElementById('like-btn');
    if (!likeButton) return;

    //articleId를 맨 위에서 꺼내서 initLikeState에서도 사용 가능
    const articleId = document.getElementById('article-id')?.value;
    if (!articleId) return;

    let likeInFlight = false;
    let reqSeq = 0;

    function applyLikeUI(data) {
        const likeCountEl = document.getElementById('like-count');
        if (likeCountEl) likeCountEl.innerText = data.likeCount;

        if (data.liked) {
            likeButton.innerText = '❤️ 좋아요 취소';
            likeButton.classList.remove('btn-outline-dark');
            likeButton.classList.add('btn-dark');
        } else {
            likeButton.innerText = '🤍 좋아요';
            likeButton.classList.remove('btn-dark');
            likeButton.classList.add('btn-outline-dark');
        }
    }

    //페이지 들어올 때 현재 liked 상태 초기 세팅
    function initLikeState() {
        httpRequestJson(
            'GET',
            `/api/articles/${articleId}`,
            null,
            (data) => applyLikeUI(data),
            () => {
                // 로그인 안 했거나 토큰 문제면 기본값 유지
            }
        );
    }

    initLikeState();

    likeButton.addEventListener('click', () => {
        if (likeInFlight) return;

        likeInFlight = true;
        likeButton.disabled = true;

        const mySeq = ++reqSeq;

        httpRequestJson(
            'POST',
            `/api/articles/${articleId}/like`,
            null,
            (data) => {
                if (mySeq !== reqSeq) return;
                applyLikeUI(data);
                likeInFlight = false;
                likeButton.disabled = false;
            },
            () => {
                if (mySeq !== reqSeq) return;
                alert('좋아요 처리 실패 (로그인 필요)');
                likeInFlight = false;
                likeButton.disabled = false;
            }
        );
    });
});