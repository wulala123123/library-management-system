import { expect, it } from 'vitest'
import { displayPrice, displayTotalUses, displayValue } from './display'

it('keeps known zero values and leading zeroes while marking missing source values', () => {
  expect(displayValue(0)).toBe('0')
  expect(displayValue('0000123')).toBe('0000123')
  expect(displayValue('原有简介', '待补充')).toBe('原有简介')
  for (const value of [null, undefined, '', '  ']) expect(displayValue(value)).toBe('未设置')
  expect(displayPrice(0)).toBe('¥0.00')
  expect(displayPrice(null)).toBe('未设置')
})

it('distinguishes explicit unlimited uses from zero and unknown uses', () => {
  expect(displayTotalUses(null)).toBe('不限')
  expect(displayTotalUses(0)).toBe('0')
  expect(displayTotalUses(12)).toBe('12')
  expect(displayTotalUses(undefined)).toBe('待核实')
})
