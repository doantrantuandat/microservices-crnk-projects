# Storefront UI Implementation Plan

**Goal:** Build a NextJS 14 storefront UI for end users and admins, communicating exclusively through the storefront-gateway.

**Architecture:** NextJS App Router on port 3000, all API calls go through the gateway on port 28080. Gateway exposes 16 new REST endpoints aggregating the 3 crnk backend services. HTTP Basic auth from browser → gateway.

**Tech Stack:** NextJS 14, TypeScript strict, Tailwind CSS v3, shadcn/ui (Radix), D3.js v7, Zustand.

**Spec:** `docs/superpowers/specs/2026-10-07-storefront-ui-design.md`

---

## Global Constraints

- Gateway: Boot 4.1.1, Jakarta, Jackson 3 (`tools.jackson.*`), port 28080
- Backend services: accounts (28081), catalog (28082), ordering (28083)
- Admin credentials: `admin:demo-password-not-for-real-use` (ADMIN role)
- User credentials: `viewer:viewer-demo` (USER role)
- Auth: HTTP Basic stored as `btoa("user:pass")` in localStorage
- All DTOs as Java records in `StorefrontController.java`
- Paged responses: `{ data: T[], total: number, page: number, size: number }`

---

## Review Focus

1. Browser Basic auth → gateway → 401 on expired session — redirect to /login
2. Cart persisted in localStorage — survives refresh, not tab close
3. D3 relationship graph handles empty data gracefully
4. Admin role guard on all `/admin/*` routes — viewer users cannot access
5. Gateway timeout → toast "Service unavailable"

---

### Task 1: Gateway SecurityConfig Update

**Files:**
- Modify: `storefront-gateway/src/main/java/io/github/doantrantuandat/example/gateway/config/SecurityConfig.java`

**Interfaces:**
- Produces: Updated SecurityFilterChain with USER/ADMIN roles

- [ ] **Step 1: Read current SecurityConfig**

Read: `storefront-gateway/src/main/java/io/github/doantrantuandat/example/gateway/config/SecurityConfig.java`

- [ ] **Step 2: Update SecurityConfig**

Update `SecurityConfig.java`:
- Add `USER` role to both users (admin already has ADMIN, viewer already has USER)
- Update path matchers: ADMIN-only for `POST /api/accounts`, `POST /api/products`, `PUT`, `DELETE`, `/api/admin/**`
- Keep open: `/api/products`, `/api/accounts` (read), `/api/orders/{id}`, `/api/orders/{id}/summary`, `/api/health`

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/accounts/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/orders/*/summary").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/orders/*").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/orders").authenticated()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/accounts").hasRole("ADMIN")
            .requestMatchers(HttpMethod.POST, "/api/products").hasRole("ADMIN")
            .requestMatchers(HttpMethod.PUT, "/api/accounts/**").hasRole("ADMIN")
            .requestMatchers(HttpMethod.PUT, "/api/products/**").hasRole("ADMIN")
            .requestMatchers(HttpMethod.DELETE, "/api/accounts/**").hasRole("ADMIN")
            .requestMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("ADMIN")
            .requestMatchers(HttpMethod.DELETE, "/api/orders/**").hasRole("ADMIN")
            .anyRequest().authenticated()
        )
        .httpBasic(Customizer.withDefaults());
    return http.build();
}
```

- [ ] **Step 3: Commit**

```bash
git add storefront-gateway/src/main/java/io/github/doantrantuandat/example/gateway/config/SecurityConfig.java
git commit -m "feat(gateway): update SecurityConfig for USER/ADMIN role split"
```

---

### Task 2: Gateway New Endpoints

**Files:**
- Modify: `storefront-gateway/src/main/java/io/github/doantrantuandat/example/gateway/StorefrontController.java`

**Interfaces:**
- Produces: 16 new endpoints listed below

- [ ] **Step 1: Read current StorefrontController**

Read: `storefront-gateway/src/main/java/io/github/doantrantuandat/example/gateway/StorefrontController.java`

- [ ] **Step 2: Write new StorefrontController with all endpoints**

Add these endpoint groups to `StorefrontController.java`:

**Products (ADMIN-protected mutations):**
- `GET /api/products?search=&minPrice=&maxPrice=&page=&size=` → paged `{ data: Product[], total, page, size }`
- `GET /api/products/{id}` → ProductDetail (product + owner Account)
- `POST /api/products` (ADMIN) → create Product, body `{ sku, name, price, description }`
- `PUT /api/products/{id}` (ADMIN) → update Product
- `DELETE /api/products/{id}` (ADMIN) → delete Product

**Accounts (ADMIN-protected mutations):**
- `GET /api/accounts?search=&plan=&page=&size=` → paged
- `GET /api/accounts/{id}` → Account
- `POST /api/accounts` (ADMIN) → create Account, body `{ name, email, plan }`
- `PUT /api/accounts/{id}` (ADMIN) → update Account
- `DELETE /api/accounts/{id}` (ADMIN) → delete Account

**Orders:**
- `GET /api/orders?accountId=&page=&size=` → paged OrderSummary (no include)
- `DELETE /api/orders/{id}` (ADMIN) → delete Order

**Admin:**
- `GET /api/admin/stats` (ADMIN) → `{ totalAccounts, totalProducts, totalOrders, totalRevenue }`
- `GET /api/admin/service-health` (ADMIN) → `{ accounts, catalog, ordering }` (UP/DOWN)
- `GET /api/admin/relationships` (ADMIN) → `{ nodes: [], edges: [] }` for D3 graph

```java
// Add after existing endpoints. Full implementation with:
// - ProductVm: id, sku, name, price, description, ownerId, ownerName
// - AccountVm: id, name, email, plan, createdAt
// - OrderListVm: id, orderNumber, accountId, accountName, total, createdAt
// - StatsVm: totalAccounts, totalProducts, totalOrders, totalRevenue
// - GraphVm: nodes[], edges[]
// - PageVm<T>: data[], total, page, size
```

Implementation notes:
- Search uses crnk QuerySpec `filter[name][contains]=X`
- Stats: count accounts, products, orders; sum of line totals as revenue
- Relationship graph: traverse all accounts → their orders → order lines → products
- Health: call each `/actuator/health` with 2s timeout

- [ ] **Step 3: Add shadow classes for graph nodes**

Create: `storefront-gateway/src/main/java/io/github/doantrantuandat/example/gateway/remote/GraphNode.java`

```java
public record GraphNode(String id, String type, String label, Object data) {}
public record GraphEdge(String from, String to, String label) {}
```

- [ ] **Step 4: Commit**

```bash
git add storefront-gateway/src/main/java/io/github/doantrantuandat/example/gateway/StorefrontController.java
git add storefront-gateway/src/main/java/io/github/doantrantuandat/example/gateway/remote/GraphNode.java
git commit -m "feat(gateway): add all CRUD + admin endpoints for storefront UI"
```

---

### Task 3: NextJS Scaffold

**Files:**
- Create: `storefront-ui/` (all scaffolded files)

- [ ] **Step 1: Create NextJS app**

```bash
npx create-next-app@latest storefront-ui --typescript --tailwind --app --eslint --no-src-dir --import-alias "@/*"
```

- [ ] **Step 2: Install dependencies**

```bash
cd storefront-ui
npm install @radix-ui/react-dialog @radix-ui/react-dropdown-menu @radix-ui/react-toast @radix-ui/react-sheet @radix-ui/react-tabs @radix-ui/react-avatar d3 zustand sonner clsx tailwind-merge class-variance-authority lucide-react
npm install --save-dev @types/d3
```

- [ ] **Step 3: Initialize shadcn**

```bash
npx shadcn@latest init --defaults
```

Add components:
```bash
npx shadcn@latest add button card dialog dropdown-menu input label select sheet skeleton sonner table tabs toast badge separator avatar
```

- [ ] **Step 4: Configure tailwind.config.ts**

Update theme colors to match spec:
```ts
colors: {
  slate: { 900: '#0f172a' },
  primary: { DEFAULT: '#2563eb', hover: '#1d4ed8' },
  danger: { DEFAULT: '#dc2626', hover: '#b91c1c' },
}
```

- [ ] **Step 5: Commit scaffold**

```bash
git add storefront-ui/
git commit -m "feat(storefront-ui): scaffold NextJS 14 project with shadcn"
```

---

### Task 4: Core Library Files

**Files:**
- Create: `storefront-ui/lib/types.ts`
- Create: `storefront-ui/lib/api.ts`
- Create: `storefront-ui/lib/auth.ts`
- Create: `storefront-ui/lib/cart-store.ts`

- [ ] **Step 1: Write lib/types.ts**

```typescript
export interface Product {
  id: number;
  sku: string;
  name: string;
  price: number;
  description?: string;
  ownerId?: number;
  ownerName?: string;
}

export interface Account {
  id: number;
  name: string;
  email: string;
  plan: 'free' | 'basic' | 'pro' | 'enterprise';
  createdAt?: string;
}

export interface OrderLine {
  id: number;
  qty: number;
  productId: number;
  productName?: string;
  unitPrice?: number;
  lineTotal?: number;
}

export interface Order {
  id: number;
  orderNumber: string;
  accountId: number;
  accountName?: string;
  total: number;
  createdAt: string;
  lines?: OrderLine[];
}

export interface PageVm<T> {
  data: T[];
  total: number;
  page: number;
  size: number;
}

export interface CartItem {
  product: Product;
  qty: number;
}

export interface Stats {
  totalAccounts: number;
  totalProducts: number;
  totalOrders: number;
  totalRevenue: number;
}

export interface GraphNode {
  id: string;
  type: 'account' | 'order' | 'product' | 'orderLine';
  label: string;
  data: Record<string, unknown>;
}

export interface GraphEdge {
  from: string;
  to: string;
  label: string;
}

export interface ServiceHealth {
  accounts: 'UP' | 'DOWN';
  catalog: 'UP' | 'DOWN';
  ordering: 'UP' | 'DOWN';
}
```

- [ ] **Step 2: Write lib/api.ts**

```typescript
const BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:28080';

function getAuth(): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem('auth');
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const auth = getAuth();
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(auth ? { 'Authorization': `Basic ${auth}` } : {}),
      ...init.headers,
    },
  });

  if (res.status === 401) {
    localStorage.removeItem('auth');
    window.location.href = '/login';
    throw new Error('Session expired');
  }
  if (res.status === 403) throw new Error('Permission denied');
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || `HTTP ${res.status}`);
  }
  return res.json();
}

export const api = {
  products: {
    list: (params: Record<string, string> = {}) =>
      request<PageVm<Product>>(`/api/products?${new URLSearchParams(params)}`),
    get: (id: number) => request<Product>(`/api/products/${id}`),
    create: (body: Partial<Product>) => request<Product>('/api/products', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: number, body: Partial<Product>) => request<Product>(`/api/products/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    delete: (id: number) => request<void>(`/api/products/${id}`, { method: 'DELETE' }),
  },
  accounts: {
    list: (params: Record<string, string> = {}) =>
      request<PageVm<Account>>(`/api/accounts?${new URLSearchParams(params)}`),
    get: (id: number) => request<Account>(`/api/accounts/${id}`),
    create: (body: Partial<Account>) => request<Account>('/api/accounts', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: number, body: Partial<Account>) => request<Account>(`/api/accounts/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
    delete: (id: number) => request<void>(`/api/accounts/${id}`, { method: 'DELETE' }),
  },
  orders: {
    list: (params: Record<string, string> = {}) =>
      request<PageVm<Order>>(`/api/orders?${new URLSearchParams(params)}`),
    get: (id: number) => request<Order>(`/api/orders/${id}`),
    create: (body: { accountId: number; lines: { productId: number; qty: number }[] }) =>
      request<Order>('/api/orders', { method: 'POST', body: JSON.stringify(body) }),
    delete: (id: number) => request<void>(`/api/orders/${id}`, { method: 'DELETE' }),
  },
  admin: {
    stats: () => request<Stats>('/api/admin/stats'),
    health: () => request<ServiceHealth>('/api/admin/service-health'),
    relationships: () => request<{ nodes: GraphNode[]; edges: GraphEdge[] }>('/api/admin/relationships'),
  },
};
```

- [ ] **Step 3: Write lib/auth.ts**

```typescript
'use client';
import { create } from 'zustand';

interface AuthState {
  role: 'admin' | 'viewer' | null;
  accountId: number | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  check: () => void;
}

export const useAuth = create<AuthState>((set) => ({
  role: null,
  accountId: null,
  login: async (username, password) => {
    const creds = btoa(`${username}:${password}`);
    localStorage.setItem('auth', creds);
    // Determine role by attempting admin endpoint
    const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:28080'}/api/admin/stats`, {
      headers: { 'Authorization': `Basic ${creds}` },
    });
    if (res.ok) {
      set({ role: 'admin', accountId: null });
    } else {
      set({ role: 'viewer', accountId: 1 }); // default viewer account
    }
  },
  logout: () => {
    localStorage.removeItem('auth');
    set({ role: null, accountId: null });
  },
  check: () => {
    const auth = localStorage.getItem('auth');
    if (!auth) { set({ role: null }); return; }
    // Silent re-check via stats endpoint
    fetch(`${process.env.NEXT_PUBLIC_API_URL || 'http://localhost:28080'}/api/admin/stats`, {
      headers: { 'Authorization': `Basic ${auth}` },
    }).then(r => r.ok ? set({ role: 'admin' }) : set({ role: 'viewer' }))
      .catch(() => set({ role: 'viewer' }));
  },
}));
```

- [ ] **Step 4: Write lib/cart-store.ts**

```typescript
'use client';
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { CartItem, Product } from './types';

interface CartState {
  items: CartItem[];
  addItem: (product: Product, qty?: number) => void;
  removeItem: (productId: number) => void;
  updateQty: (productId: number, qty: number) => void;
  clear: () => void;
  total: () => number;
}

export const useCart = create<CartState>()(
  persist(
    (set, get) => ({
      items: [],
      addItem: (product, qty = 1) => {
        const existing = get().items.find(i => i.product.id === product.id);
        if (existing) {
          set(s => ({ items: s.items.map(i => i.product.id === product.id ? { ...i, qty: i.qty + qty } : i) }));
        } else {
          set(s => ({ items: [...s.items, { product, qty }] }));
        }
      },
      removeItem: (productId) => set(s => ({ items: s.items.filter(i => i.product.id !== productId) })),
      updateQty: (productId, qty) => {
        if (qty <= 0) { get().removeItem(productId); return; }
        set(s => ({ items: s.items.map(i => i.product.id === productId ? { ...i, qty } : i) }));
      },
      clear: () => set({ items: [] }),
      total: () => get().items.reduce((sum, i) => sum + i.product.price * i.qty, 0),
    }),
    { name: 'storefront-cart' }
  )
);
```

- [ ] **Step 5: Commit**

```bash
git add storefront-ui/lib/
git commit -m "feat(storefront-ui): core library files - types, api, auth, cart"
```

---

### Task 5: Layout & Navigation Components

**Files:**
- Create: `storefront-ui/app/globals.css`
- Create: `storefront-ui/components/Navbar.tsx`
- Create: `storefront-ui/components/CartSidebar.tsx`
- Create: `storefront-ui/app/(shop)/layout.tsx`
- Create: `storefront-ui/app/(admin)/layout.tsx`
- Create: `storefront-ui/app/(auth)/login/page.tsx`
- Modify: `storefront-ui/app/layout.tsx`

- [ ] **Step 1: Write globals.css**

```css
@tailwind base;
@tailwind components;
@tailwind utilities;

@layer base {
  body {
    @apply bg-slate-900 text-slate-100;
  }
}
```

- [ ] **Step 2: Write Navbar component**

Navbar with:
- Left: Logo "Storefront" + nav links (role-aware)
- Right: Cart icon (badge with item count), user role badge, Logout
- User links: Products, My Orders, Cart
- Admin links: above + Admin dropdown (Dashboard, Accounts, Products, Orders, Relationships)
- Mobile: hamburger menu

- [ ] **Step 3: Write CartSidebar component**

- shadcn Sheet slide-over from right
- List items: product name, unit price, qty +/- controls, remove button
- Sticky footer: Total + "Place Order" button
- Calls `api.orders.create()` → clears cart on success

- [ ] **Step 4: Write app/(auth)/login/page.tsx**

- Centered card with email/password form
- Demo credentials shown: `admin:demo-password-not-for-real-use`, `viewer:viewer-demo`
- On submit: calls `useAuth.login()`
- Success: redirect to /products
- Error: toast with message

- [ ] **Step 5: Write root layout.tsx**

- Wrap with AuthProvider (useAuth)
- Include Navbar
- Include Sonner for toasts
- Dark theme by default

- [ ] **Step 6: Write shop layout.tsx**

- Include CartSidebar (slide-over)
- All (shop) pages use this layout

- [ ] **Step 7: Write admin layout.tsx**

- Check `useAuth().role === 'admin'` — if not, redirect to /products
- Sidebar nav for admin sections

- [ ] **Step 8: Commit**

```bash
git add storefront-ui/components/Navbar.tsx storefront-ui/components/CartSidebar.tsx
git add storefront-ui/app/globals.css storefront-ui/app/layout.tsx
git add storefront-ui/app/\(auth\)/login/page.tsx
git add storefront-ui/app/\(shop\)/layout.tsx storefront-ui/app/\(admin\)/layout.tsx
git commit -m "feat(storefront-ui): layout + navigation components"
```

---

### Task 6: Product Screens

**Files:**
- Create: `storefront-ui/components/ProductCard.tsx`
- Create: `storefront-ui/components/SkeletonCard.tsx`
- Create: `storefront-ui/app/(shop)/products/page.tsx`
- Create: `storefront-ui/app/(shop)/products/[id]/page.tsx`

- [ ] **Step 1: Write ProductCard component**

- Image placeholder (colored div with SKU initials)
- Product name, price
- "Add to Cart" button → calls useCart.addItem()
- "Out of stock" state (show when product.stock === 0, future-proof)

- [ ] **Step 2: Write SkeletonCard component**

- Loading placeholder matching ProductCard dimensions
- Animated pulse effect

- [ ] **Step 3: Write products/page.tsx**

- Search bar (debounced 300ms, server-side)
- Price filter sidebar (min/max inputs)
- Responsive grid: 1→2→3→4 columns
- Skeleton loading on initial fetch
- Empty state: "No products found"
- Pagination

- [ ] **Step 4: Write products/[id]/page.tsx**

- Product info: SKU, name, price, description
- Owner badge (account name)
- Quantity selector + "Add to Cart" button
- Back to catalog link

- [ ] **Step 5: Commit**

```bash
git add storefront-ui/components/ProductCard.tsx storefront-ui/components/SkeletonCard.tsx
git add storefront-ui/app/\(shop\)/products/page.tsx storefront-ui/app/\(shop\)/products/\[id\]/page.tsx
git commit -m "feat(storefront-ui): product catalog + detail pages"
```

---

### Task 7: Cart & Checkout

**Files:**
- Create: `storefront-ui/app/(shop)/cart/page.tsx`

- [ ] **Step 1: Write cart page**

- Full-page cart (alternative to sidebar)
- Line items: product, unit price, qty +/-, line total, remove
- Order total
- "Place Order" button → POST /api/orders
- Success: toast + redirect to /orders
- Empty state: "Your cart is empty" + "Continue Shopping" link

- [ ] **Step 2: Commit**

```bash
git add storefront-ui/app/\(shop\)/cart/page.tsx
git commit -m "feat(storefront-ui): cart page with checkout"
```

---

### Task 8: Order Screens

**Files:**
- Create: `storefront-ui/components/OrderTimeline.tsx`
- Create: `storefront-ui/app/(shop)/orders/page.tsx`
- Create: `storefront-ui/app/(shop)/orders/[id]/page.tsx`

- [ ] **Step 1: Write OrderTimeline component**

- Timeline of orders: Order #, Date, # items, Total
- Expandable row showing line items

- [ ] **Step 2: Write orders/page.tsx**

- Fetch user's orders (GET /api/orders)
- OrderTimeline display
- Empty state if no orders

- [ ] **Step 3: Write orders/[id]/page.tsx**

- Order number, date
- Account info
- Line items table: product, qty, unit price, line total
- Order total
- Back to orders link

- [ ] **Step 4: Commit**

```bash
git add storefront-ui/components/OrderTimeline.tsx
git add storefront-ui/app/\(shop\)/orders/page.tsx storefront-ui/app/\(shop\)/orders/\[id\]/page.tsx
git commit -m "feat(storefront-ui): order history + detail pages"
```

---

### Task 9: Admin Dashboard

**Files:**
- Create: `storefront-ui/components/StatsCard.tsx`
- Create: `storefront-ui/components/ServiceHealthBadge.tsx`
- Create: `storefront-ui/app/(admin)/page.tsx`

- [ ] **Step 1: Write StatsCard component**

- Stat card with: label, value, optional trend
- Styled with shadcn Card

- [ ] **Step 2: Write ServiceHealthBadge component**

- Green "UP" / Red "DOWN" badge
- Tooltip with last-checked time

- [ ] **Step 3: Write admin page (dashboard)**

- Stats cards: Total Accounts, Products, Orders, Revenue
- Service health badges
- Quick actions: "Add Product", "Add Account"
- Recent orders table (last 10)

- [ ] **Step 4: Commit**

```bash
git add storefront-ui/components/StatsCard.tsx storefront-ui/components/ServiceHealthBadge.tsx
git add storefront-ui/app/\(admin\)/page.tsx
git commit -m "feat(storefront-ui): admin dashboard page"
```

---

### Task 10: Admin Account Management

**Files:**
- Create: `storefront-ui/components/AccountTable.tsx`
- Create: `storefront-ui/app/(admin)/accounts/page.tsx`

- [ ] **Step 1: Write AccountTable component**

- Columns: ID, Name, Email, Plan, Created At, Actions
- Search bar (debounced)
- Pagination
- Actions: Edit (inline Dialog), Delete (ConfirmDialog)
- "Add Account" button opens create Dialog

- [ ] **Step 2: Write accounts/page.tsx**

- Mount AccountTable with API calls
- Validation: name required, email format, plan in [free, basic, pro, enterprise]

- [ ] **Step 3: Commit**

```bash
git add storefront-ui/components/AccountTable.tsx
git add storefront-ui/app/\(admin\)/accounts/page.tsx
git commit -m "feat(storefront-ui): admin account management"
```

---

### Task 11: Admin Product Management

**Files:**
- Create: `storefront-ui/components/ProductTable.tsx`
- Create: `storefront-ui/app/(admin)/products/page.tsx`

- [ ] **Step 1: Write ProductTable component**

- Columns: ID, SKU, Name, Price, Owner, Actions
- Search + pagination
- Edit/Delete actions with dialogs
- "Add Product" button

- [ ] **Step 2: Write products/page.tsx (admin)**

- Mount ProductTable

- [ ] **Step 3: Commit**

```bash
git add storefront-ui/components/ProductTable.tsx
git add storefront-ui/app/\(admin\)/products/page.tsx
git commit -m "feat(storefront-ui): admin product management"
```

---

### Task 12: Admin Order Management

**Files:**
- Create: `storefront-ui/components/OrderTable.tsx`
- Create: `storefront-ui/app/(admin)/orders/page.tsx`

- [ ] **Step 1: Write OrderTable component**

- Columns: Order #, Account, Date, # items, Total, Actions
- Filter: by account, by date range
- View detail (expand inline)
- Delete (ADMIN only)

- [ ] **Step 2: Write orders/page.tsx (admin)**

- Mount OrderTable

- [ ] **Step 3: Commit**

```bash
git add storefront-ui/components/OrderTable.tsx
git add storefront-ui/app/\(admin\)/orders/page.tsx
git commit -m "feat(storefront-ui): admin order management"
```

---

### Task 13: Relationship Explorer

**Files:**
- Create: `storefront-ui/components/RelationshipGraph.tsx`
- Create: `storefront-ui/app/(admin)/relationships/page.tsx`
- Create: `storefront-ui/components/ConfirmDialog.tsx`

- [ ] **Step 1: Write ConfirmDialog component**

- Reusable confirmation dialog for destructive actions
- Title, description, confirm/cancel buttons

- [ ] **Step 2: Write RelationshipGraph component**

- D3.js force-directed graph
- Node shapes: Account (blue circle), Order (green square), Product (orange triangle), OrderLine (grey diamond)
- Edges: Account → Order, Order → OrderLine, OrderLine → Product
- Interactions: drag nodes, zoom/pan, click node → detail panel
- Controls: zoom in/out, reset, fit to screen, filter by account

- [ ] **Step 3: Write relationships/page.tsx**

- Full page with RelationshipGraph
- Controls: "Load all", "Load by account ID"
- Node detail panel on click

- [ ] **Step 4: Commit**

```bash
git add storefront-ui/components/ConfirmDialog.tsx
git add storefront-ui/components/RelationshipGraph.tsx
git add storefront-ui/app/\(admin\)/relationships/page.tsx
git commit -m "feat(storefront-ui): relationship explorer with D3 graph"
```

---

### Task 14: Testing & Verification

- [ ] **Step 1: Run NextJS dev server**

```bash
cd storefront-ui && npm run dev
```

- [ ] **Step 2: Smoke test all screens**

1. Login as admin → verify admin nav visible
2. Login as viewer → verify only user nav
3. Product catalog: search + filter works
4. Add to cart → cart badge updates
5. Place order → order appears in order history
6. Admin dashboard: stats load, health badges show
7. Admin accounts: CRUD operations
8. Admin products: CRUD operations
9. Admin orders: view + delete
10. Relationship explorer: D3 graph renders with data

- [ ] **Step 3: Push all commits**

```bash
git push origin master
```

- [ ] **Step 4: Final commit**

```bash
git add --all && git commit -m "feat(storefront-ui: complete UI for end users and admins"
git push origin master
```
