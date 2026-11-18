import { Buffer } from 'node:buffer'
import { StrictMode } from 'react'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import useAuthStore from '../features/auth/store'
import AuthCallbackPage from './AuthCallbackPage'

let fetchMock

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  cleanup()
  useAuthStore.getState().logout()
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function Destination() {
  const { pathname, search, hash } = useLocation()
  return <div>{pathname + search + hash}</div>
}

function renderCallback() {
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={['/auth/callback']}>
        <Routes>
          <Route path="/auth/callback" element={<AuthCallbackPage />} />
          <Route path="*" element={<Destination />} />
        </Routes>
      </MemoryRouter>
    </StrictMode>,
  )
}

function validResponse() {
  const claims = { token_use: 'access', exp: Math.floor(Date.now() / 1000) + 3600, id: 7 }
  const token = 'header.' + Buffer.from(JSON.stringify(claims)).toString('base64url') + '.signature'
  return { ok: true, json: () => Promise.resolve({ accessToken: token }) }
}

it('shares a single POST across effect replays and restores the full return path', async () => {
  sessionStorage.setItem('loginRedirect', '/my-bookings?filter=upcoming#top')
  fetchMock.mockResolvedValue(validResponse())
  renderCallback()

  await screen.findByText('/my-bookings?filter=upcoming#top')
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock).toHaveBeenCalledWith('/oauth2/exchange', expect.objectContaining({
    method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error',
  }))
  expect(useAuthStore.getState().user.id).toBe(7)
  expect(sessionStorage.getItem('loginRedirect')).toBeNull()
})

it.each(['//elsewhere.example/', '/\\elsewhere.example/', '/a/..//elsewhere.example/',
  '/auth/callback?again=1', '/auth/%63allback'])(
  'returns home for an unsafe or recursive return path %s', async (path) => {
    sessionStorage.setItem('loginRedirect', path)
    fetchMock.mockResolvedValue(validResponse())
    renderCallback()
    await screen.findByText('/')
  },
)

it('preserves the return path and offers a fresh login when the exchange response is lost', async () => {
  sessionStorage.setItem('loginRedirect', '/my-bookings')
  fetchMock.mockRejectedValue(new TypeError('network error'))
  renderCallback()

  await screen.findByRole('alert')
  expect(screen.getByRole('link', { name: '로그인 다시 시작' }).getAttribute('href')).toBe('/login?error=oauth')
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(useAuthStore.getState().token).toBeNull()
  expect(sessionStorage.getItem('loginRedirect')).toBe('/my-bookings')
})

it('ignores an exchange result that arrives after leaving the callback page', async () => {
  sessionStorage.setItem('loginRedirect', '/my-bookings')
  let resolve
  fetchMock.mockImplementation(() => new Promise(success => { resolve = success }))
  const view = renderCallback()
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
  view.unmount()
  await act(async () => { resolve(validResponse()) })

  expect(useAuthStore.getState().token).toBeNull()
  expect(sessionStorage.getItem('loginRedirect')).toBe('/my-bookings')
})
