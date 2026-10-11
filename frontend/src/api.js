export async function api(path, options = {}) {
  const res = await fetch('/api' + path, {
    method: options.method || 'GET',
    headers: {
      ...(options.body && !(options.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}),
      ...(options.headers || {}),
    },
    body: options.body instanceof FormData ? options.body : options.body ? JSON.stringify(options.body) : undefined,
  })
  if (!res.ok) {
    let message = 'Request failed (' + res.status + ')'
    try {
      const data = await res.json()
      if (data.message) message = data.message
    } catch (e) {
      // keep the default message
    }
    throw new Error(message)
  }
  return res.json()
}

export const money = (n) =>
  Number(n).toLocaleString('en-US', { style: 'currency', currency: 'USD' })

export const percent = (n) => (Number(n) * 100).toFixed(1) + '%'
