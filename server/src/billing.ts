import { randomUUID } from 'node:crypto';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import type { Database } from './database.js';
import { checkoutMinutes, midtransNotification, type Midtrans } from './midtrans.js';

const amount = 19_000;
const days = 30;
const periodMs = days * 86_400_000;
const checkoutMs = checkoutMinutes * 60_000;
const creationTimeoutMs = 30_000;
type Order = { id: string; parent_id: string; amount: number; created_at: number; checkout_url: string | null };

export function billing(app: FastifyInstance, db: Database, now: () => number, parentId: (req: FastifyRequest) => Promise<string>, midtrans?: Midtrans) {
  function openOrder(parent: string) {
    return db.one<Order>("SELECT * FROM billing_orders WHERE parent_id=? AND status='open'", parent);
  }

  async function summary(parent: string) {
    // Each paid order adds one period, starting at payment or after the previous period, whichever is later.
    const access = await db.one<{ expires_at: number | null }>(`
      SELECT MAX(paid_at + remaining * CAST(? AS BIGINT)) AS expires_at FROM (
        SELECT paid_at, ROW_NUMBER() OVER (ORDER BY paid_at DESC,id DESC) AS remaining
        FROM billing_orders WHERE parent_id=? AND status='paid'
      ) periods`, periodMs, parent);
    const expiresAt = access?.expires_at ?? null;
    return { amount, days, expiresAt, pending: Boolean(await openOrder(parent)) };
  }

  if (!midtrans) return { summary: async () => null };
  const provider = midtrans;

  async function closeUnpaid(order: Order) {
    await db.change("UPDATE billing_orders SET status='closed', checkout_url=NULL WHERE id=? AND status='open'", order.id);
  }

  async function reconcile(order: Order) {
    const payment = await provider.lookup(order.id);
    if (!payment) {
      if (order.created_at + checkoutMs <= now()) await closeUnpaid(order);
      return;
    }
    if (payment.order_id !== order.id || Number(payment.gross_amount) !== order.amount) throw Error('Midtrans transaction does not match the stored order.');
    const status = payment.transaction_status;
    if (status === 'refund' || status === 'chargeback') {
      await db.change("UPDATE billing_orders SET status='refunded', checkout_url=NULL WHERE id=?", order.id);
    } else if (payment.status_code === '200' && (status === 'settlement' || status === 'capture') &&
      (!payment.fraud_status || payment.fraud_status === 'accept')) {
      await db.change(`UPDATE billing_orders SET status='paid', paid_at=?, checkout_url=NULL
        WHERE id=? AND status IN ('open','closed')`, now(), order.id);
    } else if (['cancel', 'deny', 'expire', 'failure'].includes(status)) {
      await closeUnpaid(order);
    }
  }

  async function refreshOrder(parent: string) {
    const order = await openOrder(parent);
    if (!order) return;
    if (order.checkout_url) await reconcile(order);
    else if (order.created_at + creationTimeoutMs <= now()) await closeUnpaid(order);
    return order;
  }

  const checkoutLimit = { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } };
  app.post('/billing/checkout', checkoutLimit, async (req, reply) => {
    const parent = await parentId(req);
    const previous = await refreshOrder(parent);
    if (previous) {
      const open = await openOrder(parent);
      const checkoutUrl = open && open.created_at + checkoutMs > now() ? open.checkout_url : null;
      return { billing: await summary(parent), checkoutUrl };
    }
    const order = await db.one<Order>(`INSERT INTO billing_orders(id,parent_id,amount,created_at,status)
      VALUES(?,?,?,?,'open') ON CONFLICT DO NOTHING RETURNING *`, `jgb-${randomUUID()}`, parent, amount, now());
    if (!order) return { billing: await summary(parent), checkoutUrl: null };

    let checkoutUrl: string;
    try {
      checkoutUrl = await provider.createCheckout(order, days);
    } catch (error) {
      await closeUnpaid(order);
      req.log.error({ err: error }, 'Midtrans Sandbox checkout failed');
      return reply.code(502).send({ message: 'Checkout Midtrans belum bisa dibuka. Coba lagi sebentar.' });
    }
    const saved = await db.change("UPDATE billing_orders SET checkout_url=? WHERE id=? AND status='open'", checkoutUrl, order.id);
    return { billing: await summary(parent), checkoutUrl: saved.changes ? checkoutUrl : null };
  });

  app.post('/billing/sync', checkoutLimit, async (req) => {
    const parent = await parentId(req);
    await refreshOrder(parent);
    return { billing: await summary(parent), checkoutUrl: null };
  });

  app.post('/billing/midtrans/notify', { bodyLimit: 16 * 1024 }, async (req, reply) => {
    const notification = midtransNotification.parse(req.body);
    if (!provider.validNotification(notification)) return reply.code(403).send({ message: 'Notifikasi pembayaran tidak valid.' });
    const order = await db.one<Order>('SELECT * FROM billing_orders WHERE id=?', notification.order_id);
    if (order) await reconcile(order);
    return { ok: true };
  });

  app.get('/billing/return', async (_req, reply) => reply.type('text/plain; charset=utf-8').send(
    'Kembali ke JagaBocil, buka Keluarga, lalu pilih Cek pembayaran untuk melihat status Plus. Ini transaksi uji coba Midtrans Sandbox.',
  ));

  return { summary };
}
