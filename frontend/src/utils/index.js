export const JSONStringify = data => JSON.stringify(data)

export const JSONParse = data => {
  if (typeof data !== 'string') return data
  try {
    return JSON.parse(data)
  } catch (_error) {
    return null
  }
}
