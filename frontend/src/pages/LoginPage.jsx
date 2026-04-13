import { useEffect } from "react";
import { Link, useNavigate, useLocation } from "react-router-dom";
import { loginWithGoogle } from "../features/auth/api";
import useAuthStore from "../features/auth/store";

export default function LoginPage() {
  const { token } = useAuthStore();
  const navigate = useNavigate();
  const location = useLocation();
  const from = location.state?.from || "/";

  // 이미 로그인된 경우 원래 페이지 또는 홈으로
  useEffect(() => {
    if (token) navigate(from, { replace: true });
  }, [token, navigate, from]);

  return (
    <div className="min-h-[calc(100svh-56px)] flex items-center justify-center px-4 pb-20 md:pb-0 bg-gray-50">
      <div className="w-full max-w-sm">
        {/* 로고 */}
        <div className="text-center mb-8">
          <span className="text-3xl font-black text-primary-600">TICKETFLOW</span>
          <p className="mt-2 text-sm text-gray-500">콘서트 티켓 예매 플랫폼</p>
        </div>

        {/* 카드 */}
        <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-8">
          <div className="text-center mb-8">
            <h1 className="text-xl font-bold text-gray-900 mb-1">로그인</h1>
            <p className="text-sm text-gray-400">
              Google 계정으로 빠르게 로그인하세요
            </p>
          </div>

          {/* Google 로그인 버튼 */}
          <button
            onClick={() => {
              // OAuth2 완료 후 돌아올 경로 저장
              if (from !== "/") sessionStorage.setItem("loginRedirect", from);
              loginWithGoogle();
            }}
            className="w-full flex items-center justify-center gap-3 bg-white hover:bg-gray-50 border-2 border-gray-200 hover:border-primary-300 text-gray-700 font-semibold py-3.5 rounded-xl transition-colors duration-150 shadow-sm"
          >
            <GoogleIcon />
            Google로 로그인
          </button>

          <div className="mt-6 flex items-center gap-3 text-gray-200">
            <div className="flex-1 h-px bg-gray-100" />
            <span className="text-xs text-gray-300">또는</span>
            <div className="flex-1 h-px bg-gray-100" />
          </div>

          <p className="mt-6 text-sm text-center text-gray-500">
            아직 계정이 없으신가요?{" "}
            <Link
              to="/register"
              className="text-primary-600 font-semibold hover:underline"
            >
              회원가입
            </Link>
          </p>
        </div>

        <p className="mt-4 text-xs text-center text-gray-300">
          로그인 시 서비스 이용약관에 동의하게 됩니다.
        </p>
      </div>
    </div>
  );
}

function GoogleIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 48 48" fill="none">
      <path
        d="M43.611 20.083H42V20H24v8h11.303c-1.649 4.657-6.08 8-11.303 8-6.627 0-12-5.373-12-12s5.373-12 12-12c3.059 0 5.842 1.154 7.961 3.039l5.657-5.657C34.046 6.053 29.268 4 24 4 12.955 4 4 12.955 4 24s8.955 20 20 20 20-8.955 20-20c0-1.341-.138-2.65-.389-3.917z"
        fill="#FFC107"
      />
      <path
        d="M6.306 14.691l6.571 4.819C14.655 15.108 18.961 12 24 12c3.059 0 5.842 1.154 7.961 3.039l5.657-5.657C34.046 6.053 29.268 4 24 4 16.318 4 9.656 8.337 6.306 14.691z"
        fill="#FF3D00"
      />
      <path
        d="M24 44c5.166 0 9.86-1.977 13.409-5.192l-6.19-5.238A11.91 11.91 0 0124 36c-5.202 0-9.619-3.317-11.283-7.946l-6.522 5.025C9.505 39.556 16.227 44 24 44z"
        fill="#4CAF50"
      />
      <path
        d="M43.611 20.083H42V20H24v8h11.303a12.04 12.04 0 01-4.087 5.571l.003-.002 6.19 5.238C36.971 39.205 44 34 44 24c0-1.341-.138-2.65-.389-3.917z"
        fill="#1976D2"
      />
    </svg>
  );
}
