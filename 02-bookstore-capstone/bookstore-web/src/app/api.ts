import { Injectable, signal, computed } from '@angular/core';
import { HttpClient, HttpInterceptorFn } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/**
 * Every request is relative — `/api/...`, never an absolute host.
 *
 * That is the one decision in this file that matters. Compiling the API's address into the bundle
 * would produce a build that only works against one server, so the image tag would mean "this commit,
 * for that host" rather than "this commit" — and Step 11 spent its time making a tag mean one build
 * forever. nginx decides what `/api` means at run time (see nginx.conf); `ng serve` uses
 * proxy.conf.json to say the same thing locally.
 */
const API = '/api';

export interface Book {
  id: number;
  title: string;
  isbn: string;
  price: number;
  stock: number;
  authorName: string;
  coverUrl: string | null;
}

export interface OrderItem { bookId: number; bookTitle: string; quantity: number; unitPrice: number; }
export interface Order {
  id: number;
  status: string;
  totalPrice: number;
  items: OrderItem[];
  createdAt: string;
}

export interface HistoryEntry { bookId: number; title: string; viewedAt: string; }

/**
 * The platform's paged envelope.
 *
 * **Not every endpoint uses it, and assuming otherwise is a bug that TypeScript cannot catch.** The
 * catalogue and a user's orders are paged; browsing history is a bare array, because it is already
 * bounded by a `limit` and a TTL rather than by a page number.
 *
 * Typing `GET /api/orders` as `Order[]` compiled cleanly, returned 200, and rendered an empty list —
 * an interface is a compile-time promise about a runtime shape nobody checked. Found by driving the
 * UI and reading the response body, which is the only place the truth was.
 */
export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; }

@Injectable({ providedIn: 'root' })
export class Api {
  // The token lives in a signal so the whole UI reacts to logging in and out, and in localStorage so a
  // refresh does not log you out. It is a bearer token: whoever holds it is the user, which is why it
  // is dropped on logout rather than merely hidden.
  readonly token = signal<string | null>(localStorage.getItem('token'));
  readonly username = signal<string | null>(localStorage.getItem('username'));
  readonly loggedIn = computed(() => this.token() !== null);

  constructor(private http: HttpClient) {}

  async register(username: string, email: string, password: string) {
    await firstValueFrom(
      this.http.post(`${API}/auth/register`, { username, email, password }));
  }

  async login(username: string, password: string) {
    const res = await firstValueFrom(
      this.http.post<{ token: string }>(`${API}/auth/login`, { username, password }));
    this.token.set(res.token);
    this.username.set(username);
    localStorage.setItem('token', res.token);
    localStorage.setItem('username', username);
  }

  logout() {
    this.token.set(null);
    this.username.set(null);
    localStorage.removeItem('token');
    localStorage.removeItem('username');
  }

  // The catalogue is public — no token required, which is the platform's own rule rather than a
  // convenience: browsing is PUBLIC, ordering is USER, editing is ADMIN.
  books() {
    return firstValueFrom(this.http.get<Page<Book>>(`${API}/books?size=20`));
  }

  // Reading one book is what records a browsing-history entry in DynamoDB — asynchronously, on the
  // server, so this call is not slowed down by it.
  book(id: number) {
    return firstValueFrom(this.http.get<Book>(`${API}/books/${id}`));
  }

  order(bookId: number, quantity: number) {
    return firstValueFrom(
      this.http.post<Order>(`${API}/orders`, { items: [{ bookId, quantity }] }));
  }

  // Paged, unlike history. Returning `.content` here rather than making every caller unwrap it keeps
  // the difference between the two endpoints in one place instead of in every component.
  async myOrders(): Promise<Order[]> {
    const page = await firstValueFrom(this.http.get<Page<Order>>(`${API}/orders`));
    return page.content ?? [];
  }

  pay(orderId: number) {
    return firstValueFrom(this.http.post(`${API}/payments`, { orderId }));
  }

  history() {
    return firstValueFrom(this.http.get<HistoryEntry[]>(`${API}/books/me/history`));
  }
}

/**
 * Attaches the bearer token to every outgoing request.
 *
 * Reads it from localStorage rather than injecting `Api`, because `Api` injects `HttpClient` and
 * having the interceptor depend on the service that depends on the client is a circular injection the
 * error message for is famously unhelpful.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = localStorage.getItem('token');
  return token
    ? next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))
    : next(req);
};
