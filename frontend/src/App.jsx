import { BrowserRouter, Routes, Route, useLocation } from 'react-router-dom'
import Header from './components/layout/Header'
import BottomNav from './components/layout/BottomNav'
import HomePage from './pages/HomePage'
import ConcertListPage from './pages/ConcertListPage'
import ConcertDetailPage from './pages/ConcertDetailPage'
import QueuePage from './pages/QueuePage'
import BookingPage from './pages/BookingPage'
import PaymentPage from './pages/PaymentPage'
import MyBookingsPage from './pages/MyBookingsPage'
import PaymentSuccessPage from './pages/PaymentSuccessPage'
import PaymentFailPage from './pages/PaymentFailPage'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import AdminConcertPage from './pages/AdminConcertPage'
import AuthCallbackPage from './pages/AuthCallbackPage'
import AuthGate from './features/auth/AuthGate'
import useAuthStore from './features/auth/store'

function Layout() {
  const { pathname } = useLocation()
  const isAdmin = pathname.startsWith('/admin')

  return (
    <div className="font-sans min-h-screen bg-gray-50">
      <Header />
      <Routes>
        <Route path="/"                     element={<HomePage />} />
        <Route path="/concerts"             element={<ConcertListPage />} />
        <Route path="/concerts/:id"         element={<ConcertDetailPage />} />
        <Route path="/concerts/:id/queue"   element={<QueuePage />} />
        <Route path="/concerts/:id/booking" element={<BookingPage />} />
        <Route path="/concerts/:id/payment" element={<PaymentPage />} />
        <Route path="/my-bookings"          element={<MyBookingsPage />} />
        <Route path="/payment/success"      element={<PaymentSuccessPage />} />
        <Route path="/payment/fail"         element={<PaymentFailPage />} />
        <Route path="/login"                element={<LoginPage />} />
        <Route path="/register"             element={<RegisterPage />} />
        <Route path="/admin/concerts"       element={<AdminConcertPage />} />
      </Routes>
      {!isAdmin && <BottomNav />}
    </div>
  )
}

export default function App() {
  const userId = useAuthStore(state => state.user?.id)
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/auth/callback" element={<AuthCallbackPage />} />
        <Route path="*" element={<AuthGate><Layout key={userId ?? 'anonymous'} /></AuthGate>} />
      </Routes>
    </BrowserRouter>
  )
}
