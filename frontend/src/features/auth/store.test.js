import { Buffer } from 'node:buffer'
import { afterEach, expect, it } from 'vitest'
import useAuthStore from './store'
import { setAccessToken } from './session'

const future = () => Math.floor(Date.now() / 1000) + 3600

function token(claims) {
  const payload = Buffer.from(JSON.stringify(claims)).toString('base64url')
  return `eyJhbGciOiJIUzI1NiJ9.${payload}.signature`
}

afterEach(() => {
  useAuthStore.getState().logout()
  localStorage.clear()
})

it.each([
  ['legacy', () => token({ exp: future() })],
  ['refresh', () => token({ token_use: 'refresh', exp: future() })],
  ['unknown', () => token({ token_use: 'unknown', exp: future() })],
  ['expired', () => token({ token_use: 'access', exp: 1 })],
  ['missing expiration', () => token({ token_use: 'access' })],
  ['malformed', () => 'invalid-jwt'],
])('rejects a %s token at login', (_case, makeToken) => {
  const value = makeToken()
  expect(setAccessToken(value)).toBe(false)
  expect(useAuthStore.getState()).toMatchObject({ token: null, user: null })
})

it('preserves a valid access token and uses its UTF-8 payload for the current user', () => {
  const claims = { token_use: 'access', exp: future(), sub: '공연🎟️@example.test', id: 1, role: 'USER' }
  const accessToken = token(claims)
  expect(setAccessToken(accessToken)).toBe(true)
  expect(useAuthStore.getState()).toMatchObject({ token: accessToken, user: claims })

  expect(localStorage.getItem('auth-storage')).toBeNull()
})
