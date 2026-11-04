import { describe, expect, it } from 'vitest';
import { readModelRequestError } from './chatModelErrors';

describe('pre-stream model errors', () => {
  it('recognizes a history incompatibility without exposing arbitrary error bodies', async () => {
    expect(await readModelRequestError(new Response(JSON.stringify({ message: 'MODEL_PROVIDER_STATE_INCOMPATIBLE' }), { status: 400 })))
      .toBe('MODEL_PROVIDER_STATE_INCOMPATIBLE');
    expect(await readModelRequestError(new Response(JSON.stringify({ message: 'Authorization: secret' }), { status: 502 }))).toBeNull();
  });
  it('handles non-JSON gateway errors safely', async () => {
    expect(await readModelRequestError(new Response('<html>gateway error</html>', { status: 502 }))).toBeNull();
  });
});
