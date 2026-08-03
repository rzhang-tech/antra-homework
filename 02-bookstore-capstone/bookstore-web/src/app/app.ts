import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api, Book, Order, HistoryEntry } from './api';

/**
 * The whole application, in one component.
 *
 * Deliberately: the assignment makes a frontend optional, and this one exists to make the platform
 * demonstrable in a browser rather than to be an example of Angular architecture. Splitting three
 * screens across routed feature modules would add files without adding anything a viewer can see.
 * What it does do is exercise every layer of the backend — public catalogue, authentication, a
 * cross-service order, a payment, and both AWS-backed reads.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  readonly api = inject(Api);

  books = signal<Book[]>([]);
  orders = signal<Order[]>([]);
  history = signal<HistoryEntry[]>([]);
  message = signal<{ text: string; kind: 'ok' | 'err' } | null>(null);
  busy = signal(false);

  username = '';
  password = '';
  email = '';

  async ngOnInit() {
    await this.loadBooks();
    if (this.api.loggedIn()) await this.refreshUserData();
  }

  private say(text: string, kind: 'ok' | 'err' = 'ok') {
    this.message.set({ text, kind });
    setTimeout(() => this.message.set(null), 6000);
  }

  // Every failure the platform can produce arrives here, and showing the real status code is the point:
  // the platform is deliberate about them. 401 unauthenticated, 403 wrong role, 409 out of stock,
  // 503 a dependency is down and this failed fast rather than hanging.
  private fail(e: any, what: string) {
    const status = e?.status ?? 0;
    const detail = e?.error?.message ?? e?.error?.error ?? '';
    this.say(`${what} failed - HTTP ${status}${detail ? ': ' + detail : ''}`, 'err');
  }

  async loadBooks() {
    try {
      this.books.set((await this.api.books()).content);
    } catch (e) { this.fail(e, 'Loading the catalogue'); }
  }

  async refreshUserData() {
    try { this.orders.set(await this.api.myOrders()); } catch { /* not fatal */ }
    try { this.history.set(await this.api.history()); } catch { /* needs AWS */ }
  }

  async register() {
    this.busy.set(true);
    try {
      await this.api.register(this.username, this.email || `${this.username}@example.com`, this.password);
      await this.api.login(this.username, this.password);
      this.say(`Registered and signed in as ${this.username}`);
      await this.refreshUserData();
    } catch (e) { this.fail(e, 'Registration'); }
    finally { this.busy.set(false); }
  }

  async login() {
    this.busy.set(true);
    try {
      await this.api.login(this.username, this.password);
      this.say(`Signed in as ${this.username}`);
      await this.refreshUserData();
    } catch (e) { this.fail(e, 'Sign in'); }
    finally { this.busy.set(false); }
  }

  logout() {
    this.api.logout();
    this.orders.set([]);
    this.history.set([]);
    this.say('Signed out');
  }

  // Opening a book records a browsing-history entry in DynamoDB. The write is asynchronous on the
  // server, so this returns at catalogue speed and the history list catches up a moment later.
  async view(b: Book) {
    try {
      await this.api.book(b.id);
      this.say(`Viewed "${b.title}" - recorded in DynamoDB`);
      setTimeout(() => this.refreshUserData(), 1200);
    } catch (e) { this.fail(e, 'Opening the book'); }
  }

  async buy(b: Book) {
    this.busy.set(true);
    try {
      const order = await this.api.order(b.id, 1);
      this.say(`Order ${order.id} placed - ${order.status}`);
      await this.loadBooks();          // stock changed, in another service's database
      await this.refreshUserData();
    } catch (e) { this.fail(e, 'Placing the order'); }
    finally { this.busy.set(false); }
  }

  async pay(o: Order) {
    this.busy.set(true);
    try {
      await this.api.pay(o.id);
      this.say(`Order ${o.id} paid - a receipt and a sales tally are on their way over Kafka`);
      await this.refreshUserData();
    } catch (e) { this.fail(e, 'Payment'); }
    finally { this.busy.set(false); }
  }
}
