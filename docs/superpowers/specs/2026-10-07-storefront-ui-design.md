# Storefront UI — NextJS Application Spec

> **Goal:** A unified e-commerce UI for end users and admins, built on NextJS 14 App Router, communicating exclusively through the existing storefront-gateway. All backend complexity lives in the gateway's extended REST API.

## Context

The workspace contains 4 services using `io.github.doantrantuandat:crnk-framework-lts:4.0.0-lts.2` from Maven Central. The `storefront-gateway` (Spring Boot 4.1.1) is the only ingress point — it aggregates all 3 backend crnk services via `CrnkClient`. This project adds a NextJS frontend that exposes all domain capabilities to end users and admins through extended gateway endpoints.

## Architecture

```
NextJS App (port 3000)
  └── HTTP/JSON → Storefront Gateway (port 28080)
                    ├── CrnkClient → accounts-service :28081
                    ├── CrnkClient → catalog-service :28082
                    └── CrnkClient → ordering-service :28083
```

**Rule:** All frontend-to-backend communication routes through the gateway. No direct calls to backend services from the browser. The gateway is the single security perimeter.

---

## 1. Gateway API Extension

### 1.1 Security Changes

Update `SecurityConfig.java`:
- Require ADMIN role for all mutation endpoints (`POST /api/accounts`, `POST /api/products`, `PUT`, `DELETE`, `/api/admin/**`)
- Keep `/api/products`, `/api/accounts` (read), `/api/orders/{id}`, `/api/orders/{id}/summary`, `/api/health` open

**InMemoryUserDetailsManager** now holds two users:
```java
User.withUsername("admin")
    .password("{noop}demo-password-not-for-real-use").roles("ADMIN").build()
User.withUsername("viewer")
    .password("{noop}viewer-demo").roles("USER").build()
```

HTTP Basic auth from browser → gateway.

### 1.2 New Gateway Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/products` | open | List products. Query: `?search=`, `?minPrice=`, `?maxPrice=`, `?page=`, `?size=` |
| GET | `/api/products/{id}` | open | Product detail with owner Account |
| POST | `/api/products` | ADMIN | Create product |
| PUT | `/api/products/{id}` | ADMIN | Update product |
| DELETE | `/api/products/{id}` | ADMIN | Delete product |
| GET | `/api/accounts` | open | List accounts. Query: `?search=`, `?plan=`, `?page=`, `?size=` |
| GET | `/api/accounts/{id}` | open | Account detail |
| POST | `/api/accounts` | ADMIN | Create account |
| PUT | `/api/accounts/{id}` | ADMIN | Update account (name, email, plan) |
| DELETE | `/api/accounts/{id}` | ADMIN | Delete account |
| GET | `/api/orders` | open | List orders. Query: `?accountId=`, `?page=`, `?size=` |
| GET | `/api/orders/{id}` | open | Order detail with all relationships resolved |
| GET | `/api/orders/{id}/lines` | open | Lines for an order |
| DELETE | `/api/orders/{id}` | ADMIN | Delete order |
| GET | `/api/admin/stats` | ADMIN | Dashboard statistics |
| GET | `/api/admin/service-health` | ADMIN | Per-service health |

**Implementation notes:**
- Paged responses use `{ data: T[], total: number, page: number, size: number }`
- Search is performed server-side via crnk QuerySpec's `filter[name][contains]=X`
- Stats response: `{ totalAccounts, totalProducts, totalOrders, totalRevenue }`
- All gateway endpoints use existing CrnkClient beans — no new CrnkClient instances needed

---

## 2. NextJS Project Structure

```
storefront-ui/                    # NextJS 14, TypeScript, App Router
├── app/
│   ├── layout.tsx               # Root layout: Navbar + auth context
│   ├── page.tsx                 # → /dashboard (redirect based on role)
│   ├── (auth)/
│   │   └── login/page.tsx      # Login page
│   ├── (shop)/
│   │   ├── layout.tsx          # Shop layout: nav + cart sidebar
│   │   ├── products/
│   │   │   ├── page.tsx       # Product catalog
│   │   │   └── [id]/page.tsx  # Product detail
│   │   ├── cart/page.tsx       # Shopping cart
│   │   └── orders/
│   │       ├── page.tsx        # Order history
│   │       └── [id]/page.tsx  # Order detail
│   ├── (admin)/
│   │   ├── layout.tsx          # Admin layout: admin-only guard
│   │   ├── page.tsx            # Admin dashboard
│   │   ├── accounts/page.tsx    # Account management
│   │   ├── products/page.tsx   # Product management
│   │   ├── orders/page.tsx     # Order management
│   │   └── relationships/page.tsx # Relationship explorer
│   └── globals.css
├── components/
│   ├── Navbar.tsx              # Top nav with role-aware links + cart icon
│   ├── CartSidebar.tsx         # Slide-over cart panel
│   ├── ProductCard.tsx         # Product grid card
│   ├── ProductTable.tsx         # Admin table with inline edit
│   ├── AccountTable.tsx        # Admin account table
│   ├── OrderTable.tsx          # Admin order table
│   ├── OrderTimeline.tsx       # User order history with expandable lines
│   ├── StatsCard.tsx          # Dashboard stat card
│   ├── ServiceHealthBadge.tsx  # UP/DOWN badge per service
│   ├── RelationshipGraph.tsx   # D3.js force-directed graph
│   ├── SkeletonCard.tsx       # Loading skeleton
│   ├── Toast.tsx              # Error/success notifications
│   └── ConfirmDialog.tsx      # Destructive action confirmation
├── lib/
│   ├── api.ts                  # Fetch wrapper with auth header injection
│   ├── auth.ts                 # Auth context (Zustand or Context API)
│   ├── cart-store.ts           # Cart state (localStorage persistence)
│   └── types.ts                # All TypeScript interfaces matching gateway DTOs
├── public/
└── package.json
```

**Tech stack:**
- NextJS 14 (App Router, Server Components where appropriate)
- TypeScript strict mode
- Tailwind CSS v3
- shadcn/ui (Radix primitives — Dialog, DropdownMenu, Toast, etc.)
- D3.js v7 (relationship graph only)
- Zustand (cart + auth state, persisted to localStorage)

---

## 3. Screens

### 3.1 Public / End-User Screens

#### Login (`/login`)
- Email + password form (HTTP Basic auth from browser)
- Demo credentials displayed: `admin:demo-password-not-for-real-use`, `viewer:viewer-demo`
- Error toast on failed auth
- Redirect to `/products` on success

#### Product Catalog (`/products`)
- Responsive grid (1→2→3→4 columns)
- Search bar (debounced 300ms, server-side filter)
- Price filter sidebar (min/max inputs)
- ProductCard: image placeholder (colored div with SKU), name, price, "Add to Cart" button
- "Out of stock" state (if ever added)
- Empty state: "No products found"
- Skeleton loading on initial fetch

#### Product Detail (`/products/[id]`)
- Product info: SKU, name, price, description placeholder
- Owner (Account) info shown as linked badge
- Quantity selector + "Add to Cart" button
- Back to catalog link

#### Cart (`/cart`)
- Slide-over sidebar (always accessible via nav icon)
- Line items: product name, unit price, qty +/- controls, line total, remove button
- Order total (sum of line totals)
- "Place Order" button → POST `/api/orders` via gateway → success toast + clear cart
- Empty state: "Your cart is empty"
- Persisted to localStorage — survives page refresh

#### Order History (`/orders`)
- Timeline/table of user's orders
- Columns: Order #, Date, # items, Total
- Expandable row → OrderTimeline showing line items with product info
- Click → Order Detail page

#### Order Detail (`/orders/[id]`)
- Order number, date
- Account info (name, email, plan)
- Line items table: product, qty, unit price, line total
- Order total
- Back to orders list

### 3.2 Admin Screens

**All admin screens: sidebar nav, require ADMIN role.**

#### Admin Dashboard (`/admin`)
- Stats cards row: Total Accounts, Total Products, Total Orders, Total Revenue
- Service health badges: accounts/catalog/ordering — green UP / red DOWN
- Quick actions: "Add Product", "Add Account"
- Recent orders table (last 10)

#### Account Management (`/admin/accounts`)
- Searchable, paginated table
- Columns: ID, Name, Email, Plan, Created At, Actions
- Actions: Edit (inline modal), Delete (confirm dialog)
- "Add Account" button → modal form
- Validation: name required, email format, plan must be one of [free, basic, pro, enterprise]

#### Product Management (`/admin/products`)
- Searchable, paginated table
- Columns: ID, SKU, Name, Price, Owner, Actions
- Actions: Edit (inline modal), Delete (confirm dialog)
- "Add Product" button → modal form
- Validation: SKU required + unique, name required, price > 0

#### Order Management (`/admin/orders`)
- Paginated table of all orders
- Columns: Order #, Account, Date, # items, Total, Actions
- Actions: View detail (expand inline), Delete
- Filter: by account, by date range
- Order detail expansion: full line items + account info

#### Relationship Explorer (`/admin/relationships`)
- D3.js force-directed graph
- Node types: Account (blue circle), Order (green square), Product (orange triangle), OrderLine (grey diamond)
- Edges: Account → Order (own), Order → OrderLine (contains), OrderLine → Product (references)
- Interactions: drag nodes, zoom/pan, click node → detail panel (shows all fields)
- Controls: "Load all", "Load by account ID", zoom reset
- Real data fetched via `GET /api/relationships` (new gateway endpoint returning graph structure)

---

## 4. Component Specifications

### Navbar
- Left: Logo "Storefront" + role-aware nav links
- Right: Cart icon (item count badge), user role badge, Logout
- User sees: Products, My Orders, Cart
- Admin sees above + Admin (dropdown: Dashboard, Accounts, Products, Orders, Relationships)
- Mobile: hamburger menu

### CartSidebar
- Slide-over from right (shadcn Sheet)
- Header: "Shopping Cart" + close button
- Line items list (scrollable)
- Sticky footer: Total + "Checkout" button
- Checkout: POST `/api/orders` with `{ accountId: viewerAccountId, lines: [...] }` — creates order and clears cart

### RelationshipGraph
- `useEffect` + D3 force simulation
- Data: `{ nodes: Node[], edges: Edge[] }` fetched from gateway
- Node rendering: SVG shapes per entity type, label below
- Edge rendering: SVG lines with arrows
- Detail panel: slides in from right on node click, shows all node fields
- Controls: zoom in/out buttons, "Reset zoom", "Fit to screen"
- Responsive: fills container, redraws on resize

### Toast Notifications
- shadcn Toast (sonner)
- Types: success (green), error (red), info (blue)
- Auto-dismiss: 4s for success/info, sticky for errors
- Positions: top-right

---

## 5. API Integration

### Auth Flow (Browser → Gateway)
```
Browser stores Basic auth header in localStorage:
  btoa("username:password")

On every API call:
  fetch('/api/...', {
    headers: { 'Authorization': 'Basic ' + storedCreds }
  })

On 401 response:
  Clear stored creds
  Redirect to /login
```

### Key API Calls

**Products:**
```typescript
GET /api/products?search=&minPrice=&maxPrice=&page=&size=
→ { data: Product[], total: number, page: number, size: number }
```

**Cart → Order:**
```typescript
POST /api/orders
Body: { accountId: number, lines: [{ productId: number, qty: number }] }
Auth: Basic admin creds
→ OrderSummary
```

**Stats (admin):**
```typescript
GET /api/admin/stats
Auth: Basic admin creds
→ { totalAccounts: number, totalProducts: number, totalOrders: number, totalRevenue: number }
```

**Relationship graph:**
```typescript
GET /api/admin/relationships
Auth: Basic admin creds
→ { nodes: [{ id, type, label, data: object }], edges: [{ from, to, label }] }
```

---

## 6. Error Handling

| Scenario | UI Behavior |
|----------|-------------|
| API 401 | Redirect to /login, toast "Session expired" |
| API 403 | Toast "Permission denied" |
| API 404 | Inline "Not found" message |
| API 422 | Toast with validation message from response |
| API 5xx | Toast "Service unavailable, please try again" |
| Network error | Toast "Connection error" |
| Gateway timeout | Same as 5xx |

---

## 7. Styling

- **Theme**: Dark mode primary (background `#0f172a`), light mode via `prefers-color-scheme`
- **Font**: Inter (Google Fonts) or system font stack
- **Spacing**: Tailwind default scale
- **Border radius**: `rounded-lg` for cards, `rounded-full` for badges
- **Colors**:
  - Background: `bg-slate-900` (dark) / `bg-white` (light)
  - Primary action: `bg-blue-600 hover:bg-blue-700`
  - Danger: `bg-red-600 hover:bg-red-700`
  - Success: `bg-green-600`
  - Muted text: `text-slate-400`

---

## 8. File Decomposition

### Gateway changes (storefront-gateway)
1. `SecurityConfig.java` — add USER role, update path rules
2. `StorefrontController.java` — add all new endpoints (see section 1.2)
3. `StorefrontGatewayApplication.java` — add `@EnableMethodSecurity` if needed for role checks

### NextJS project
1. Scaffold: `npx create-next-app@latest storefront-ui --typescript --tailwind --app`
2. Install: `npm install @radix-ui/react-dialog @radix-ui/react-dropdown-menu @radix-ui/react-toast @radix-ui/react-sheet @radix-ui/react-dialog d3 zustand sonner`
3. Install shadcn components: `npx shadcn@latest init` then add components
4. Create `lib/types.ts`
5. Create `lib/api.ts`
6. Create `lib/auth.ts` + `lib/cart-store.ts`
7. Create all pages and components

---

## 9. Scope Boundaries

### In scope
- All 8 screens described above
- Full CRUD for admin on accounts and products
- Cart with localStorage persistence + checkout
- Relationship explorer with D3 graph
- Role-based navigation
- Dark/light mode

### Out of scope (for now)
- Real image uploads for products
- Payment processing
- Email notifications
- Order status/state machine
- User registration
- JWT/OAuth — HTTP Basic is sufficient for this demo
- Inventory management / stock levels
- Rate limiting / API keys
