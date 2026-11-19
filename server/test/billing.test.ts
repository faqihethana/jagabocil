import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { createApp } from '../src/app.js';
import { postgresDatabase } from '../src/database.js';
import { midtransSandbox } from '../src/midtrans.js';

test('Midtrans Sandbox reconciles one checkout per family and one Plus period per verified payment', async () => {
  const root = await mkdtemp(join(tmpdir(), 'jagabocil-billing-'));
  const schema = 'jagabocil_billing_' + randomUUID().replaceAll('-', '');
  const database = process.env.TEST_DATABASE_URL ? await postgresDatabase(process.env.TEST_DATABASE_URL, schema, true) : undefined;
  const key = 'SB-Mid-server-fake-for-tests';
  const period = 30 * 86_400_000;
  let now = Date.now();
  let rejectedCheckout = false;
  const orders: string[] = [];
  const statuses = new Map<string, { status: string; amount?: string; fraud?: string }>();
  const provider = midtransSandbox(key, 'https://billing.example.com', async (input, init) => {
    assert.equal(new Headers(init?.headers).get('authorization'), `Basic ${Buffer.from(`${key}:`).toString('base64')}`);
    const url = new URL(String(input));
    if (url.pathname === '/snap/v1/transactions') {
      assert.equal(url.origin, 'https://app.sandbox.midtrans.com');
      assert.equal(init?.method, 'POST');
      assert.equal(new Headers(init?.headers).get('x-append-notification'), 'https://billing.example.com/billing/midtrans/notify');
      if (rejectedCheckout) return Response.json({}, { status: 503 });
      const { transaction_details: order, item_details, callbacks } = JSON.parse(String(init.body));
      assert.equal(order.gross_amount, 19_000);
      assert.equal(item_details[0].price, 19_000);
      assert.equal(callbacks.finish, 'https://billing.example.com/billing/return');
      orders.push(order.order_id);
      return Response.json({ redirect_url: `https://app.sandbox.midtrans.com/snap/v2/vtweb/${order.order_id}` });
    }
    assert.equal(url.origin, 'https://api.sandbox.midtrans.com');
    const id = url.pathname.split('/')[2]!;
    const data = statuses.get(id);
    if (!data) return Response.json({ status_code: '404' });
    return Response.json({ status_code: data.status === 'pending' ? '201' : '200', order_id: id,
      gross_amount: data.amount ?? '19000.00', currency: 'IDR', transaction_status: data.status, fraud_status: data.fraud });
  });
  const app = await createApp({ dataDir: root, database, now: () => now, midtrans: provider });
  const request = (method: 'GET' | 'POST', url: string, token?: string, payload?: object) => app.inject({
    method, url, headers: token ? { authorization: `Bearer ${token}` } : {}, payload,
  });
  const notify = (id: string, signed = true) => request('POST', '/billing/midtrans/notify', undefined, {
    order_id: id, status_code: '200', gross_amount: '19000.00', transaction_status: 'settlement',
    signature_key: signed ? createHash('sha512').update(`${id}20019000.00${key}`).digest('hex') : '0'.repeat(128),
  });
  try {
    const register = async (username: string) => (await request('POST', '/auth/register', undefined, { username, password: '1234' })).json().token;
    const parent = await register('orangtua');
    const other = await register('oranglain');
    const profile = (await request('POST', '/children', parent, { name: 'Adek' })).json();
    const child = (await request('POST', '/pair', undefined, { code: profile.pairing.code })).json().token;
    const checkout = () => request('POST', '/billing/checkout', parent);
    const dashboard = async (token = parent) => (await request('GET', '/dashboard', token)).json().billing;

    assert.equal((await request('POST', '/billing/checkout')).statusCode, 401);
    assert.equal((await request('POST', '/billing/checkout', child)).statusCode, 403);
    assert.equal(await dashboard(child), null);
    rejectedCheckout = true;
    assert.equal((await checkout()).statusCode, 502);
    assert.equal((await dashboard()).pending, false);
    rejectedCheckout = false;

    const simultaneous = await Promise.all([checkout(), checkout()]);
    assert.ok(simultaneous.every(reply => reply.statusCode === 200));
    assert.equal(orders.length, 1);
    const first = orders[0]!;
    assert.match((await checkout()).json().checkoutUrl, new RegExp(first));
    assert.equal((await notify(first, false)).statusCode, 403);
    statuses.set(first, { status: 'pending' });
    assert.equal((await notify(first)).statusCode, 200);
    assert.equal((await dashboard()).expiresAt, null);
    statuses.set(first, { status: 'settlement', amount: '18000.00' });
    assert.equal((await notify(first)).statusCode, 500);
    assert.equal((await dashboard()).expiresAt, null);
    statuses.set(first, { status: 'capture', fraud: 'challenge' });
    assert.equal((await notify(first)).statusCode, 200);
    assert.equal((await dashboard()).expiresAt, null);
    statuses.set(first, { status: 'settlement' });
    const verified = await Promise.all([notify(first), notify(first), request('POST', '/billing/sync', parent)]);
    assert.ok(verified.every(reply => reply.statusCode === 200));
    assert.deepEqual(await dashboard(), { amount: 19_000, days: 30, expiresAt: now + period, pending: false });
    assert.equal((await dashboard(other)).expiresAt, null);
    const firstExpiry = now + period;

    now += 60_000;
    assert.equal((await checkout()).statusCode, 200);
    const second = orders[1]!;
    statuses.set(second, { status: 'settlement' });
    assert.equal((await request('POST', '/billing/sync', parent)).json().billing.expiresAt, firstExpiry + period);
    statuses.set(second, { status: 'refund' });
    assert.equal((await notify(second)).statusCode, 200);
    statuses.set(second, { status: 'settlement' });
    assert.equal((await notify(second)).statusCode, 200);
    assert.equal((await dashboard()).expiresAt, firstExpiry);

    assert.ok((await checkout()).json().checkoutUrl);
    now += 31 * 60_000;
    assert.equal((await request('POST', '/billing/sync', parent)).json().billing.pending, false);
    assert.ok((await checkout()).json().checkoutUrl);
    assert.equal(orders.length, 4);
  } finally {
    if (database) await database.change(`DROP SCHEMA "${schema}" CASCADE`);
    await app.close();
    await rm(root, { recursive: true, force: true });
  }
});
