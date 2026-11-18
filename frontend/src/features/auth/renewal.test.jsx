import { Buffer } from 'node:buffer'
import { StrictMode } from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import api, { configureApiAuth } from '../../shared/api/axios'
import { restoreBrowserSession } from './bootstrap'
import { authSnapshot, beginLogout, clearAuthSession, finishLogout, initializeAuthStorage, setAccessToken } from './session'
import AuthGate from './AuthGate'

function access(id, signature) {
  const payload = Buffer.from(JSON.stringify({
    id, token_use: 'access', exp: Math.floor(Date.now() / 1000) + 3600,
  })).toString('base64url')
  return 'header.' + payload + '.' + signature
}

const old = access(7, 'old')
const renewed = access(7, 'renewed')
const originalAdapter = api.defaults.adapter
let network

function success(token = renewed) {
  return { status: 201, json: async () => ({ accessToken: token }) }
}

function unauthorized(config) {
  return Object.assign(new Error('unauthorized'), { config, response: { status: 401 } })
}

beforeEach(() => {
  clearAuthSession()
  network = vi.fn()
  vi.stubGlobal('fetch', network)
  configureApiAuth({ authSnapshot, renew: restoreBrowserSession })
})

afterEach(() => {
  cleanup()
  clearAuthSession()
  localStorage.clear()
  api.defaults.adapter = originalAdapter
  vi.unstubAllGlobals()
})

it('removes legacy storage and waits for cookie recovery before mounting protected content', async () => {
  vi.stubGlobal('BroadcastChannel', undefined)
  localStorage.setItem('auth-storage', 'old-data')
  localStorage.setItem('access_token', 'old-data')
  initializeAuthStorage()
  expect(localStorage.getItem('auth-storage')).toBeNull()
  expect(localStorage.getItem('access_token')).toBeNull()

  let resolve
  network.mockImplementation(() => new Promise(done => { resolve = done }))
  render(<StrictMode><MemoryRouter initialEntries={['/my-bookings']}>
    <AuthGate><p>Protected view</p></AuthGate>
  </MemoryRouter></StrictMode>)
  expect(screen.queryByText('Protected view')).toBeNull()
  expect(network).toHaveBeenCalledTimes(1)
  resolve(success())

  await screen.findByText('Protected view')
  expect(authSnapshot().token).toBe(renewed)
  expect(localStorage.getItem('auth-storage')).toBeNull()
})

it('allows retry after a transient initialization error', async () => {
  network.mockResolvedValueOnce({ status: 503 }).mockResolvedValueOnce(success())
  render(<MemoryRouter><AuthGate><p>Ready view</p></AuthGate></MemoryRouter>)
  await screen.findByRole('alert')
  expect(screen.queryByText('Ready view')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '다시 확인' }))
  await screen.findByText('Ready view')
})

it('shares one renewal across GETs and a POST and replays only the two GETs once', async () => {
  setAccessToken(old)
  network.mockResolvedValue(success())
  const requests = vi.fn(async config => {
    if (config.headers.Authorization === `Bearer ${old}`) throw unauthorized(config)
    expect(config.headers.Authorization).toBe(`Bearer ${renewed}`)
    return { config, data: 'recovered' }
  })
  api.defaults.adapter = requests

  const responses = await Promise.allSettled([
    api.get('/me/bookings'), api.get('/concerts/1/payment/me'), api.post('/concerts/1/payment/toss-confirm'),
  ])
  expect(network).toHaveBeenCalledTimes(1)
  expect(responses[0].value.data).toBe('recovered')
  expect(responses[1].value.data).toBe('recovered')
  expect(responses[2].reason.authRestored).toBe(true)
  expect(requests.mock.calls.map(([config]) => config.method)).toEqual(['get', 'get', 'post', 'get', 'get'])
})

it('does not retry a stale request after the refresh cookie switches accounts', async () => {
  setAccessToken(old)
  network.mockResolvedValue(success(access(8, 'another-account')))
  const requests = vi.fn(async config => { throw unauthorized(config) })
  api.defaults.adapter = requests

  await expect(api.get('/me/bookings')).rejects.toMatchObject({ code: 'ERR_CANCELED' })
  expect(requests).toHaveBeenCalledTimes(1)
  expect(authSnapshot().userId).toBe(8)
})

it('ignores an in-flight renewal when logout completes', async () => {
  setAccessToken(old)
  let resolve
  network.mockImplementation(() => new Promise(done => { resolve = done }))
  const pending = restoreBrowserSession()
  const logout = beginLogout()
  finishLogout(logout, true)
  resolve(success())

  await expect(pending).resolves.toBe('superseded')
  expect(authSnapshot().token).toBeNull()
})

it('does not renew again when the single GET retry still returns 401', async () => {
  setAccessToken(old)
  network.mockResolvedValue(success())
  api.defaults.adapter = vi.fn(async config => { throw unauthorized(config) })
  await expect(api.get('/me/bookings')).rejects.toMatchObject({ response: { status: 401 } })
  expect(network).toHaveBeenCalledTimes(1)
  expect(api.defaults.adapter).toHaveBeenCalledTimes(2)
})

it('keeps the current account on an unavailable renewal and clears it only on 401', async () => {
  setAccessToken(old)
  network.mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce({ status: 401 })
  await expect(restoreBrowserSession()).resolves.toBe('unavailable')
  expect(authSnapshot().token).toBe(old)
  await expect(restoreBrowserSession()).resolves.toBe('anonymous')
  await waitFor(() => expect(authSnapshot().token).toBeNull())
})

it('shares only logout notifications and discards renewal from before a different tab logged out', async () => {
  const channel = { addEventListener: vi.fn(), postMessage: vi.fn() }
  vi.stubGlobal('BroadcastChannel', function () { return channel })
  initializeAuthStorage()
  setAccessToken(old)
  finishLogout(beginLogout(), true)
  expect(channel.postMessage.mock.calls).toEqual([['logout']])
  setAccessToken(old)
  let resolve
  network.mockImplementation(() => new Promise(done => { resolve = done }))
  const pending = restoreBrowserSession()
  const receive = channel.addEventListener.mock.calls[0][1]
  receive({ data: 'logout' })
  resolve(success())
  await expect(pending).resolves.toBe('superseded')
  expect(authSnapshot().token).toBeNull()
})
