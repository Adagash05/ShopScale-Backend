# Premium storefront (public store at `/s/:slug`)

Status (second pass included): **code written; unit tests, a real-browser render pass and a scripted customer journey were run against a mock API. `mvn`, `vitest`, `vite build`, real Paystack and a real backend were NOT run.** See "Test report".

## 1. What existed before
A single page (`pages/store/Storefront.jsx`) holding a product grid, a cart drawer and a checkout form, with `GET /public/store/{slug}`, `GET /public/store/{slug}/products`, `POST /public/store/{slug}/orders`, `GET /public/orders/{token}` and `POST /public/orders/{token}/verify`. No header/footer, no product pages, no information pages, no business-type awareness. It has been replaced (deleted); the merchant dashboard pages `pages/store/StoreOrders.jsx` and `StoreSettings.jsx` are untouched.

## 2. Design system (centrally controlled)
`src/store/storefront.css`, scoped under `.sf`. One palette (ink / paper / navy accent), serif headings + system sans, no webfonts, restrained radii, always light. There are no theme selectors, colour or CSS controls. Per business type only labels, layout and a subtle accent hue change (`presentation.js`).

| Business type | Layout | Wording |
|---|---|---|
| RETAIL, WHOLESALE, PRODUCTION, LIVESTOCK | photo-led grid | Shop / Listings, "Add to cart" |
| RESTAURANT, SMALL_CHOPS, CATERING | menu rows grouped by category | Menu / Packages, "Add to order" |

## 3. Pages and components (`src/store/`)
`index.jsx` (routes) · `StoreContext.jsx` (data, cart, toast) · `StoreLayout.jsx` (announcement bar, sticky header, mobile drawer, 4-column footer, sticky mobile cart bar) · `StoreHome.jsx` · `StoreShop.jsx` (search, category chips, sort, available-only, show more) · `StoreProduct.jsx` (variants, stock, quantity, buy now, facts, related) · `StoreCart.jsx` · `StoreCheckout.jsx` · `StoreOrders.jsx` (My orders + order status) · `StorePages.jsx` (About, Contact, FAQ, Shipping, Returns, Privacy, Terms, Not found) · `ui.jsx` · `presentation.js` (pure logic).

Routes: `/s/:slug`, `shop`, `product/:productId`, `cart`, `checkout`, `orders`, `order/:token`, `about`, `contact`, `faq`, `shipping`, `returns`, `privacy`, `terms`, `*`.

## 4. Data honesty
Everything comes from the existing API. Announcement bar and trust points derive from real settings (delivery, pickup, fee, pay online/later). Footer and contact show only details that exist. FAQ answers reflect actual settings. Privacy, Terms and Returns are neutral standard notices labelled as ShopScale defaults. "My orders" = order tokens remembered in this browser (`shopscale.orders.<slug>`), re-read from the server; there are no customer accounts. Cart storage key/shape unchanged (`shopscale.cart.<slug>` = `{variantId: qty}`).

Items listed here as missing in the first release (logo, sale prices, gallery, specifications, reviews, social links, newsletter, order lookup, catering enquiries, restaurant options) were added in the second pass; see section 9.

## 5. Backend change
`StoreOrderService`: `StoreInfo` gained `businessType, subtype, email, address`; `StoreItem` gained `productId, categoryId, categoryName, createdAt` (category names via `CategoryRepository.findByBusinessIdOrderByNameAsc(businessId)`, tenant-scoped). Needed for product pages, category navigation, business-type presentation and the contact/footer details. Additive only; checkout/payment contract unchanged. Note: business email and address are now public on the storefront.

## 6. Test report
Run:
- `node --test scripts/store-verify.mjs`: 11/11 (grouping, filter, sort, quantity limits, cart totals, announcement/trust/FAQ derivation, orders memory).
- esbuild bundle of all of `src/store/*` against stand-ins (compiles and links).
- `scripts/store-harness/shoot.cjs`: 10 pages x mobile 390 / tablet 820 / desktop 1366 in Chromium: no horizontal overflow, no broken images, no console errors, no failed requests.
- `scripts/store-harness/journey.cjs`: 56/56 checks (28 on desktop, 28 on mobile), two stores: search, category filter, sort, variants (sold-out variant disabled), add to cart, quantity change, subtotal arithmetic, cart persistence across reload, remove, checkout validation, pay-on-pickup order, order page content, My orders, tenant isolation (store B shows none of store A's orders, cart or products), 14 footer links resolve, external links are tel/mailto/wa.me, sold-out product has no buy buttons, missing store, empty store, unknown sub-route, no console errors.
- Harness bugs found and fixed during the run were in the tests, not the app (wrong expected total, a selector matching related-product cards). One real app defect found earlier: hero/band/footer text misaligned (padding shorthand), fixed.

Not run: `mvn test`, `npm test`/vitest, `vite build`, real `react-router-dom` and `lucide-react` (stand-ins used; icons rendered as placeholders), a real Paystack payment, the real backend with real data and JWT, real image uploads, Safari/Firefox. The online-payment redirect branch was not exercised. The existing e2e/unit tests do not cover the new storefront.

## 7a. Second-pass results
Run: `store-verify.mjs` 17/17; esbuild bundle of the whole storefront; `journey2.cjs` 90/90 (45 per viewport, desktop + mobile, four mock stores); original `journey.cjs` 56/56 re-run after the changes; 30 + 7 Chromium renders. Defects found and fixed in this pass: mobile header overflowed when a store has a logo (logo now replaces the text name below 900px). Test mistakes (not app bugs): wrong expected totals, uppercase "OFF", shared mock state.
Not run: `mvn` (the new Java, the V20 migration and the new JUnit `StoreExtrasServiceTest` have only had a Java *parse* check, 267 files, 0 syntax errors, never compiled or executed), vitest, `vite build`, real Paystack with options, real image uploads, the merchant dashboard screens (`StoreProfile`, `StorefrontDetails`, `StoreInbox` were written but never rendered or clicked).

## 7. Harness
`scripts/store-harness/` holds the stand-ins, mock server and Playwright scripts used above. They assume `/tmp/sfh` and `/opt/npm-tools`; copy them there to rerun. They are not part of the app build.

## 9. Second pass: previously deferred features
All server-side rules, no merchant design controls.
| Feature | Where it comes from | Merchant side |
|---|---|---|
| Store logo | `store_logos` table (own table so settings reads stay light), served at `/api/public/store/{slug}/logo`, 300 KB, JPEG/PNG/WebP sniffed | Online store > Logo, links and hours |
| Social links, opening hours | `store_settings` columns; links accepted only on the network's own domain (https) | same panel |
| Sale price | `product_variants.compare_at_price`; shown only while higher than the price; never used to charge | product form > Online store details |
| Extra photos | `product_images.gallery`, up to 8 | same |
| Specifications | `products.specs`, "Label: value" lines, max 20 | same |
| Reviews | `store_reviews`; only from the buyer of that item on a COMPLETED order (order link), one per order and item, held PENDING until the merchant approves; first name only is shown | Store inbox > Reviews |
| Testimonials on home | approved reviews of 4 stars or more; section absent when none | n/a |
| Newsletter | `store_subscribers`, same reply whether or not already subscribed | Store inbox > Subscribers (copy emails) |
| Catering enquiry | `store_enquiries` + notification; Enquire link only for CATERING stores | Store inbox > Enquiries |
| Order lookup | phone used on the order + order number, per store; stands in for customer accounts | n/a |
| Restaurant options | the existing menu modifier groups, shown on the storefront; chosen on the product page, validated (required, max, same product and business, active) and priced by the server; stored per order item (`store_order_item_options`); the surcharge goes to the sale like the delivery fee; ingredient stock and cost handled by `MenuService.applyOptionIngredients` | existing Menu options screens |
Cart storage: keys are a variant id, or `variantId~optionIds` when options were chosen; old carts keep working. Rate limiting now covers every public store POST. `OrderItemDto` gained `productId` (public order view only).

Still not built: customer accounts with passwords (order lookup instead); social links beyond Instagram, Facebook, X and TikTok; product reviews with photos; per-line notes; item options on the merchant's "Online orders" screen beyond the name text; email to subscribers (the list is only stored).

## 8. First actions on a networked machine
`mvn test` (watch `StoreOrderService`, `StoreExtrasService`, Flyway V20 and `PermissionAnnotationsTest`); `npm ci && npm test && npm run build`; click through the dashboard pages above; open `/s/<slug>` for two real stores; Paystack test-mode order from the storefront and confirm `/verify` marks it paid.
