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
    return firstValueFrom(this.http.get<{ content: Book[] }>(`${API}/books?size=20`));
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

  myOrders() {
    return firstValueFrom(this.http.get<Order[]>(`${API}/orders`));
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
