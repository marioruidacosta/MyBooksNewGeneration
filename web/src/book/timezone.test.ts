import { expect, it } from 'vitest'

it('aNonUtcZoneIsInEffect', () => {
  expect(new Date(0).getTimezoneOffset()).not.toBe(0)
})
