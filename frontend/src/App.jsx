import { useEffect } from 'react'
import { BrowserRouter, Routes, Route, useNavigate, useLocation } from 'react-router-dom'
import Header from './components/layout/Header'
import BottomNav from './components/layout/BottomNav'
import HomePage from './pages/HomePage'
import ConcertDetailPage from './pages/ConcertDetailPage'
import QueuePage from './pages/QueuePage'
import BookingPage from './pages/BookingPage'
import PaymentPage from './pages/PaymentPage'
import MyBookingsPage from './pages/MyBookingsPage'
import PaymentSuccessPage from './pages/PaymentSuccessPage'
import PaymentFailPage from './pages/PaymentFailPage'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import useAuthStore from './store/authStore'

/** OAuth2 콜백 처리: URL에 ?token= 이 있으면 저장 후 제거 */
function OAuthCallback() {
  const { setToken } = useAuthStore()
  const navigate = useNavigate()
  const { search } = useLocation()

  useEffect(() => {
    const params = new URLSearchParams(search)
    const token = params.get('token')
    if (token) {
      setToken(token)
      // 로그인 전 방문하려던 경로로 복귀, 없으면 홈(/)으로
      const redirect = sessionStorage.getItem('loginRedirect') || '/'
      sessionStorage.removeItem('loginRedirect')
      navigate(redirect, { replace: true })
    }
  }, [search]) // eslint-disable-line

  return null
}

function Layout() {
  return (
    <div className="font-sans min-h-screen bg-gray-50">
      <OAuthCallback />
      <Header />
      <Routes>
        <Route path="/"                     element={<HomePage />} />
        <Route path="/concerts/:id"         element={<ConcertDetailPage />} />
        <Route path="/concerts/:id/queue"   element={<QueuePage />} />
        <Route path="/concerts/:id/booking" element={<BookingPage />} />
        <Route path="/concerts/:id/payment" element={<PaymentPage />} />
        <Route path="/my-bookings"          element={<MyBookingsPage />} />
        <Route path="/payment/success"      element={<PaymentSuccessPage />} />
        <Route path="/payment/fail"         element={<PaymentFailPage />} />
        <Route path="/login"                element={<LoginPage />} />
        <Route path="/register"             element={<RegisterPage />} />
      </Routes>
      <BottomNav />
    </div>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <Layout />
    </BrowserRouter>
  )
}
