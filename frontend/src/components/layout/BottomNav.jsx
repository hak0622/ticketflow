import { Link, useLocation } from 'react-router-dom'
import useAuthStore from '../../features/auth/store'

function NavItem({ to, label, icon, active }) {
  return (
    <Link
      to={to}
      className={`flex-1 flex flex-col items-center justify-center py-2 gap-0.5 text-xs font-medium transition-colors ${
        active ? 'text-primary-600' : 'text-gray-400'
      }`}
    >
      <span className={`text-xl leading-none ${active ? 'scale-110' : ''} transition-transform`}>
        {icon}
      </span>
      <span>{label}</span>
    </Link>
  )
}

export default function BottomNav() {
  const { pathname } = useLocation()
  const { token } = useAuthStore()

  return (
    <nav className="md:hidden fixed bottom-0 left-0 right-0 z-50 bg-white border-t border-gray-100 flex safe-area-inset-bottom">
      <NavItem to="/"           label="홈"      icon="🏠" active={pathname === '/'} />
      <NavItem to="/my-bookings" label="예매내역" icon="🎫" active={pathname.startsWith('/my-bookings')} />
      <NavItem
        to={token ? '/my-bookings' : '/login'}
        label={token ? '마이페이지' : '로그인'}
        icon={token ? '👤' : '🔑'}
        active={['/login', '/register'].includes(pathname)}
      />
    </nav>
  )
}
