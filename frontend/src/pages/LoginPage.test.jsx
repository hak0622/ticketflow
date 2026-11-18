import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { loginWithGoogle } from '../features/auth/api'
import LoginPage from './LoginPage'

vi.mock('../features/auth/api', () => ({ loginWithGoogle: vi.fn() }))
vi.mock('../features/auth/store', () => ({ default: () => ({ token: null }) }))

afterEach(() => {
  cleanup()
  sessionStorage.clear()
  vi.clearAllMocks()
})

function startLogin(entry) {
  render(<MemoryRouter initialEntries={[entry]}><LoginPage /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: 'Google로 로그인' }))
  expect(loginWithGoogle).toHaveBeenCalledTimes(1)
}

it('shows a generic OAuth failure and keeps the return path when retrying', () => {
  sessionStorage.setItem('loginRedirect', '/concerts/7/booking')
  startLogin('/login?error=oauth')
  expect(screen.getByRole('alert').textContent).toMatch(/다시 시도/)
  expect(sessionStorage.getItem('loginRedirect')).toBe('/concerts/7/booking')
})

it('clears a stale return path on a fresh login', () => {
  sessionStorage.setItem('loginRedirect', '/concerts/7/booking')
  startLogin('/login')
  expect(screen.queryByRole('alert')).toBeNull()
  expect(sessionStorage.getItem('loginRedirect')).toBeNull()
})

it('saves the current protected route for post-login navigation', () => {
  startLogin({ pathname: '/login', state: { from: '/my-bookings' } })
  expect(sessionStorage.getItem('loginRedirect')).toBe('/my-bookings')
})
