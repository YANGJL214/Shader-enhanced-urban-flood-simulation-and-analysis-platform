const LOGIN_SESSION_KEY = 'medox-session-id'
const PUBLIC_SESSION_KEY = 'medox-public-session-id'
let memorySessionId = null

function generateSessionId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `pub-${crypto.randomUUID()}`
  }
  return `pub-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

export function ensureSessionId() {
  const isPublicEntry = window.location.hash.startsWith('#/public/')

  if (!isPublicEntry) {
    const loginSessionId = sessionStorage.getItem(LOGIN_SESSION_KEY)
    if (loginSessionId && !loginSessionId.startsWith('pub-')) {
      return loginSessionId
    }
  }

  let sessionId = null
  try {
    sessionId = localStorage.getItem(PUBLIC_SESSION_KEY)
    if (!sessionId) {
      const legacySessionId = sessionStorage.getItem(LOGIN_SESSION_KEY)
      if (legacySessionId?.startsWith('pub-')) {
        sessionId = legacySessionId
        localStorage.setItem(PUBLIC_SESSION_KEY, sessionId)
        sessionStorage.removeItem(LOGIN_SESSION_KEY)
      }
    }
  } catch (error) {
    sessionId = memorySessionId
  }

  if (sessionId && (!sessionId.startsWith('pub-') || sessionId.length > 50)) {
    sessionId = null
  }

  if (!sessionId) {
    sessionId = generateSessionId()
    memorySessionId = sessionId
    try {
      localStorage.setItem(PUBLIC_SESSION_KEY, sessionId)
    } catch (error) {
    }
  }
  return sessionId
}

export function isPublicEntryRoute(route) {
  return route?.meta?.publicEntry === true
}
