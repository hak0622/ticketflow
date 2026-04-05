import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { register } from '../api/auth'

export default function RegisterPage() {
  const navigate = useNavigate()
  const [form, setForm]       = useState({ email: '', password: '', confirm: '' })
  const [error, setError]     = useState('')
  const [loading, setLoading] = useState(false)
  const [done, setDone]       = useState(false)

  const handleChange = (e) => {
    setError('')
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }))
  }

  const validate = () => {
    if (!form.email.includes('@')) return '올바른 이메일을 입력해주세요.'
    if (form.password.length < 8) return '비밀번호는 8자 이상이어야 합니다.'
    if (form.password !== form.confirm) return '비밀번호가 일치하지 않습니다.'
    return null
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    const validErr = validate()
    if (validErr) { setError(validErr); return }

    setLoading(true)
    try {
      await register(form.email, form.password)
      setDone(true)
    } catch (err) {
      const status = err.response?.status
      if (status === 500) {
        setError('이미 사용 중인 이메일입니다.')
      } else {
        setError('회원가입에 실패했습니다. 다시 시도해주세요.')
      }
    } finally {
      setLoading(false)
    }
  }

  if (done) {
    return (
      <div className="min-h-[calc(100svh-56px)] flex items-center justify-center px-4 pb-20 md:pb-0 bg-gray-50">
        <div className="w-full max-w-sm text-center">
          <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-8">
            <div className="w-14 h-14 bg-primary-100 rounded-full flex items-center justify-center mx-auto mb-4">
              <span className="text-2xl">✓</span>
            </div>
            <h2 className="text-xl font-bold text-gray-900 mb-2">회원가입 완료!</h2>
            <p className="text-sm text-gray-500 mb-6">
              <span className="font-semibold text-gray-700">{form.email}</span>으로<br />
              가입이 완료되었습니다.
            </p>
            <button
              onClick={() => navigate('/login')}
              className="w-full bg-primary-500 hover:bg-primary-600 text-white font-semibold py-3.5 rounded-xl transition-colors"
            >
              로그인하러 가기
            </button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-[calc(100svh-56px)] flex items-center justify-center px-4 pb-20 md:pb-0 bg-gray-50">
      <div className="w-full max-w-sm">
        {/* 로고 */}
        <div className="text-center mb-8">
          <span className="text-3xl font-black text-primary-600">TICKETLY</span>
          <p className="mt-2 text-sm text-gray-500">지금 가입하고 공연을 예매하세요</p>
        </div>

        {/* 카드 */}
        <div className="bg-white rounded-2xl shadow-sm border border-gray-100 p-8">
          <h1 className="text-xl font-bold text-gray-900 mb-6">회원가입</h1>

          <form onSubmit={handleSubmit} className="space-y-4">
            {/* 이메일 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                이메일
              </label>
              <input
                type="email"
                name="email"
                value={form.email}
                onChange={handleChange}
                placeholder="example@email.com"
                required
                className="w-full px-4 py-3 rounded-xl border border-gray-200 text-sm focus:outline-none focus:ring-2 focus:ring-primary-400 focus:border-transparent transition"
              />
            </div>

            {/* 비밀번호 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                비밀번호
              </label>
              <input
                type="password"
                name="password"
                value={form.password}
                onChange={handleChange}
                placeholder="8자 이상 입력"
                required
                className="w-full px-4 py-3 rounded-xl border border-gray-200 text-sm focus:outline-none focus:ring-2 focus:ring-primary-400 focus:border-transparent transition"
              />
            </div>

            {/* 비밀번호 확인 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                비밀번호 확인
              </label>
              <input
                type="password"
                name="confirm"
                value={form.confirm}
                onChange={handleChange}
                placeholder="비밀번호 재입력"
                required
                className="w-full px-4 py-3 rounded-xl border border-gray-200 text-sm focus:outline-none focus:ring-2 focus:ring-primary-400 focus:border-transparent transition"
              />
            </div>

            {/* 에러 메시지 */}
            {error && (
              <p className="text-sm text-red-500 bg-red-50 px-4 py-2.5 rounded-lg">
                {error}
              </p>
            )}

            {/* 제출 버튼 */}
            <button
              type="submit"
              disabled={loading}
              className="w-full bg-primary-500 hover:bg-primary-600 disabled:opacity-50 text-white font-semibold py-3.5 rounded-xl transition-colors mt-2"
            >
              {loading ? '가입 중...' : '가입하기'}
            </button>
          </form>

          <p className="mt-6 text-sm text-center text-gray-500">
            이미 계정이 있으신가요?{' '}
            <Link
              to="/login"
              className="text-primary-600 font-semibold hover:underline"
            >
              로그인
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
