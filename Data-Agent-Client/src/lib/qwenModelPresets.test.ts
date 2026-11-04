import { describe, expect, it } from 'vitest';
import { filterQwenModels } from './qwenModelPresets';

describe('initial Qwen model candidates', () => {
  it('offers the four requested IDs when no model is entered', () => {
    const expected = ['qwen3.8-max', 'qwen3.6-flash', 'qwen-plus', 'qwen3.5-plus'];
    expect(filterQwenModels('')).toEqual(expected);
    expect(filterQwenModels('   ')).toEqual(expected);
  });
  it('matches informal names and partial IDs without substituting unknown model IDs', () => {
    expect(filterQwenModels('QWEN3.8MAX')).toEqual(['qwen3.8-max']);
    expect(filterQwenModels('qwen3.6flash')).toEqual(['qwen3.6-flash']);
    expect(filterQwenModels('qwen3max')).toEqual([]);
    expect(filterQwenModels('3.5')).toEqual(['qwen3.5-plus']);
    expect(filterQwenModels('plus')).toHaveLength(2);
    expect(filterQwenModels('qwen3.8-plus')).toEqual([]);
    expect(filterQwenModels('my-account-model')).toEqual([]);
  });
});
