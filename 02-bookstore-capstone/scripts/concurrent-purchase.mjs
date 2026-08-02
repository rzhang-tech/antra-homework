// Fires N purchase requests at the same book simultaneously, to exercise the @Version optimistic lock.
//
//   node scripts/concurrent-purchase.mjs [concurrency] [stock]
//
// Creates a fresh book with the given stock, fires `concurrency` single-copy purchases through
// Promise.all so they overlap inside the same transaction window, and reports the outcome.
//
// The point of the demo: successes + conflicts should equal the request count, and the final stock
// should equal (initial stock - successes) exactly. Without @Version, concurrent read-modify-write
// would lose updates and the arithmetic would not add up.

const HOST = process.env.HOST ?? 'http://localhost:8080';
const CONCURRENCY = Number(process.argv[2] ?? 30);
const STOCK = Number(process.argv[3] ?? 30);

const post = (path, body) =>
  fetch(`${HOST}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

const created = await post('/api/books', {
  title: 'Concurrency Test Book',
  // Must stay within BookRequestDto's @Size(max = 20) on isbn.
  isbn: `LT-${Date.now() % 1_000_000_000}`,
  price: 10.0,
  stock: STOCK,
});
if (!created.ok) {
  console.error(`could not create the test book: HTTP ${created.status}`);
  console.error(await created.text());
  process.exit(1);
}
const book = await created.json();
console.log(`book id=${book.id}  initial stock=${book.stock}`);
console.log(`firing ${CONCURRENCY} concurrent purchases of 1 copy each...\n`);

const results = await Promise.all(
  Array.from({ length: CONCURRENCY }, () =>
    post(`/api/books/${book.id}/purchase`, { quantity: 1 })
      .then((r) => r.status)
      .catch(() => 'network-error'),
  ),
);

const tally = results.reduce((acc, s) => ({ ...acc, [s]: (acc[s] ?? 0) + 1 }), {});
const ok = tally[200] ?? 0;
const conflict = tally[409] ?? 0;

const final = await (await fetch(`${HOST}/api/books/${book.id}`)).json();

console.log('HTTP status tally:', tally);
console.log(`  200 OK       (purchase succeeded) : ${ok}`);
console.log(`  409 Conflict (lost the race)      : ${conflict}`);
console.log(`\nfinal stock = ${final.stock}`);
console.log(`expected    = ${STOCK} - ${ok} = ${STOCK - ok}`);
console.log(
  final.stock === STOCK - ok
    ? '\nOK — no lost updates: every successful purchase is accounted for in the stock.'
    : `\nLOST UPDATE — ${final.stock - (STOCK - ok)} copies were sold but never deducted.`,
);
