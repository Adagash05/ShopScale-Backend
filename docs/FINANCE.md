# How the numbers are worked out

All dashboard figures come from the database for the chosen date range (in the business timezone).

| Figure | Rule |
|---|---|
| Sales | Total of completed sales. Refunded sales are excluded. |
| Cost of products sold | Cost price recorded on each sale item at the time of the sale. |
| Estimated gross profit | Sales − cost of products sold. |
| Expenses | Expenses with a date inside the range. |
| Estimated operating profit | Gross profit − expenses. |
| Money received | Payments taken on completed sales + repayments from customers, both inside the range. |
| Customers who owe you | Sum of customer balances above zero, as of now (not range-limited). |
| Stock value | Sum of cost price × quantity for active products, as of now. |

* Credit sales count as sales when made; the cash appears under money received when the customer pays.
* A refund removes the sale from sales, cost and profit, returns the stock, and cancels the sale's unpaid balance.
  Handing money back to the customer is not recorded by the system.
* If the cost price is blank it counts as zero, so profit is overstated. The app says profit is an estimate.
* Recorded cash and bank balances, supplier balances and other income are not built yet.

## Purchasing and cost (added)
* Receiving stock adds the quantity and updates the product's cost to a **weighted average**: (on-hand × current cost + received × new cost) ÷ (on-hand + received). If nothing is on hand the new cost applies.
* A purchase is **not an expense**. Its cost reaches profit when the items are sold (through cost of products sold), so counting it as an expense too would double it.
* The part of a purchase not paid becomes **what you owe the supplier**. Payments to suppliers reduce that balance; they are not expenses either.
* Stock value uses the current average cost.
