import { createHash, timingSafeEqual } from 'node:crypto';
import { z } from 'zod';

const snapOrigin = 'https://app.sandbox.midtrans.com';
const statusOrigin = 'https://api.sandbox.midtrans.com';
const checkoutResponse = z.object({ redirect_url: z.url({ protocol: /^https$/, hostname: /^app\.sandbox\.midtrans\.com$/ }) });
const statusResponse = z.object({
  status_code: z.string(), order_id: z.string(), gross_amount: z.string().regex(/^\d+(?:\.\d{1,2})?$/),
  currency: z.literal('IDR'), transaction_status: z.string(), fraud_status: z.string().optional(),
});
export const midtransNotification = z.object({
  order_id: z.string().max(50), status_code: z.string().regex(/^\d{3}$/),
  gross_amount: z.string().regex(/^\d{1,12}(?:\.\d{1,2})?$/), signature_key: z.string().regex(/^[0-9a-f]{128}$/i),
});

export const checkoutMinutes = 30;
export type Midtrans = ReturnType<typeof midtransSandbox>;

export function midtransSandbox(serverKey: string, publicOrigin?: string, request = fetch) {
  if (!serverKey.startsWith('SB-Mid-server-')) throw Error('MIDTRANS_SANDBOX_SERVER_KEY harus memakai Server Key Sandbox.');
  let origin: string | undefined;
  if (publicOrigin) {
    const parsed = new URL(publicOrigin);
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.href !== `${parsed.origin}/`) {
      throw Error('MIDTRANS_PUBLIC_ORIGIN harus berupa origin HTTPS, misalnya https://sandbox.example.com.');
    }
    origin = parsed.origin;
  }
  const finishUrl = origin ? `${origin}/billing/return` : undefined;
  const authorization = `Basic ${Buffer.from(`${serverKey}:`).toString('base64')}`;
  async function call(url: string, payload?: object) {
    const headers: Record<string, string> = { authorization, accept: 'application/json' };
    if (payload) {
      headers['content-type'] = 'application/json';
      if (origin) headers['x-append-notification'] = `${origin}/billing/midtrans/notify`;
    }
    return request(url, {
      method: payload ? 'POST' : 'GET', headers, body: payload && JSON.stringify(payload),
      redirect: 'error', signal: AbortSignal.timeout(10_000),
    });
  }
  return {
    async createCheckout(order: { id: string; amount: number; created_at: number }, days: number) {
      const startTimeWib = new Date(order.created_at + 7 * 60 * 60_000).toISOString().slice(0, 19).replace('T', ' ');
      const response = await call(`${snapOrigin}/snap/v1/transactions`, {
        transaction_details: { order_id: order.id, gross_amount: order.amount },
        item_details: [{ id: 'jagabocil-plus', name: `JagaBocil Plus ${days} hari`, price: order.amount, quantity: 1 }],
        enabled_payments: ['gopay', 'other_qris'],
        expiry: { start_time: `${startTimeWib} +0700`, unit: 'minutes', duration: checkoutMinutes },
        ...(finishUrl && { callbacks: { finish: finishUrl }, gopay: { enable_callback: true, callback_url: finishUrl } }),
      });
      if (!response.ok) throw Error(`Midtrans Snap checkout failed (HTTP ${response.status}).`);
      const result = checkoutResponse.safeParse(await response.json());
      if (!result.success) throw Error('Midtrans returned an invalid checkout URL.');
      return result.data.redirect_url;
    },
    async lookup(orderId: string) {
      const response = await call(`${statusOrigin}/v2/${encodeURIComponent(orderId)}/status`);
      if (response.status === 404) return null;
      if (!response.ok) throw Error(`Midtrans status request failed (HTTP ${response.status}).`);
      const payload = await response.json() as { status_code?: string } | null;
      if (payload?.status_code === '404') return null;
      const result = statusResponse.safeParse(payload);
      if (!result.success) throw Error('Midtrans returned an invalid transaction status.');
      return result.data;
    },
    validNotification(notification: z.infer<typeof midtransNotification>) {
      const { order_id, status_code, gross_amount, signature_key } = notification;
      const expected = createHash('sha512').update(order_id + status_code + gross_amount + serverKey).digest();
      return timingSafeEqual(expected, Buffer.from(signature_key, 'hex'));
    },
  };
}
