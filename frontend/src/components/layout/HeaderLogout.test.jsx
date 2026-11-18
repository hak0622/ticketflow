import { Buffer } from 'node:buffer'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import useAuthStore from '../../features/auth/store'
import { setAccessToken } from '../../features/auth/session'
import Header from './Header'

let fetchMock

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
  const claims = { id: 7, exp: Math.floor(Date.now() / 1000) + 600, token_use: 'access', role: 'USER' }
  const payload = Buffer.from(JSON.stringify(claims)).toString('base64url')
  setAccessToken(`header.${payload}.signature`)
})

afterEach(() => {
  cleanup()
  useAuthStore.getState().logout()
  vi.unstubAllGlobals()
})

function Path() {
  return <p data-testid="path">{useLocation().pathname}</p>
}

function renderHeader() {
  return render(<MemoryRouter initialEntries={['/my-bookings']}><Header /><Path /></MemoryRouter>)
}

it('sends one POST and clears auth only after the server acknowledges logout', async () => {
  let resolve
  fetchMock.mockImplementation(() => new Promise(done => { resolve = done }))
  renderHeader()
  const logout = screen.getByRole('button', { name: '로그아웃' })
  fireEvent.click(logout)
  fireEvent.click(logout)

  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock).toHaveBeenCalledWith('/logout', expect.objectContaining({
    method: 'POST', credentials: 'same-origin', redirect: 'error',
  }))
  expect(screen.getByRole('button', { name: '로그아웃 중' }).disabled).toBe(true)
  expect(useAuthStore.getState().token).not.toBeNull()
  await act(async () => { resolve({ status: 204 }) })
  expect(useAuthStore.getState().token).toBeNull()
  expect(screen.getByTestId('path').textContent).toBe('/')
})

it.each(['http failure', 'lost response'])('keeps the login and allows retry after %s', async (failure) => {
  if (failure === 'http failure') fetchMock.mockResolvedValueOnce({ status: 503 })
  else fetchMock.mockRejectedValueOnce(new TypeError('connection closed'))
  fetchMock.mockResolvedValueOnce({ status: 204 })

  renderHeader()
  fireEvent.click(screen.getByRole('button', { name: '로그아웃' }))
  expect((await screen.findByRole('alert')).textContent).toContain('다시 시도')
  expect(useAuthStore.getState().token).not.toBeNull()
  expect(screen.getByTestId('path').textContent).toBe('/my-bookings')
  await act(async () => { fireEvent.click(screen.getByRole('button', { name: '로그아웃' })) })
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(screen.queryByRole('alert')).toBeNull()
  expect(useAuthStore.getState().token).toBeNull()
  expect(screen.getByTestId('path').textContent).toBe('/')
})
