import { describe, expect, it } from 'vitest';
import { formatTokenBudget } from './modelSpecifications';

describe('formatTokenBudget', () => {
  it.each([
    [1_000_000, '1M'],
    [1_048_576, '1M'],
    [2_000_000, '2M'],
    [393_216, '384K'],
    [131_072, '128K'],
    [65_536, '64K'],
    [32_768, '32K'],
    [8_192, '8K'],
    [512, '512'],
  ])('renders %i tokens as %s', (tokens, expected) => {
    expect(formatTokenBudget(tokens)).toBe(expected);
  });
});
