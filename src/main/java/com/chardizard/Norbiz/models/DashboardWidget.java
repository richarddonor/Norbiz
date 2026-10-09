package com.chardizard.Norbiz.models;

/**
 * Every widget a user can pin to their dashboard. Each has its own VIEW_DASHBOARD_&lt;KEY&gt; permission,
 * seeded by DataInitializer, so an admin can grant widgets independently of the underlying transaction's
 * VIEW_ permission. Which widgets a user has pinned (and in what order) is their own UserPreference
 * (frontend key {@code dashboard.layout}), not stored here. See docs/DASHBOARD.md.
 */
public enum DashboardWidget {
    PENDING_OUTLET_RECEIVES("pending-outlet-receives", "Pending Outlet Receives", "Inventory",
            "Outlet delivery receipts still in transit: outstanding quantity and value, aging, and which outlets are waiting."),
    STOCK_IN_TRANSIT("stock-in-transit", "Stock in Transit", "Inventory",
            "In-transit quantity per warehouse (purchases on the way, outlet deliveries and pull-outs not yet received) against on-hand stock."),
    OUTLET_STOCK_HEALTH("outlet-stock-health", "Outlet Stock Health", "Inventory",
            "Stock-outs and low cover at outlets: on-hand of the best-selling items at the busiest outlets, as days of cover at the recent sales rate."),
    INVENTORY_ADJUSTMENT_TREND("inventory-adjustment-trend", "Inventory Adjustment Trend", "Inventory",
            "Units added and removed by inventory adjustments per day, by warehouse and by most common reason."),
    PENDING_PURCHASE_ORDERS("pending-purchase-orders", "Pending Purchase Orders", "Purchases",
            "Purchase orders not yet fully received or invoiced: outstanding quantity and cost, aging, and which suppliers owe deliveries."),
    UNPAID_PURCHASE_INVOICES("unpaid-purchase-invoices", "Unpaid Purchase Invoices", "Purchases",
            "Purchase invoices not yet paid: net payable, aging since invoice date, payment status and the suppliers owed the most."),
    OUTLET_SALES("outlet-sales", "Outlet Sales Pulse", "Sales",
            "Daily outlet sales net of returns, with the top outlets and agents for the period."),
    AGENT_LEADERBOARD("agent-leaderboard", "Agent Leaderboard", "Sales",
            "Agents ranked by net outlet sales (their commission base), with each agent's daily trend, return rate and change vs the prior period."),
    PULL_OUTS_AWAITING_RECEIVE("pull-outs-awaiting-receive", "Pull-outs Awaiting Receive", "Sales",
            "Outlet pull-outs not yet received back at the main warehouse: quantity and value in transit, aging, by outlet and by reason."),
    TRANSACTION_ACTIVITY("transaction-activity", "Transaction Activity", "Operations",
            "Documents posted per day for each transaction type as a heatmap, with voids and the busiest day.");

    private final String slug;
    private final String displayName;
    private final String category;
    private final String description;

    DashboardWidget(String slug, String displayName, String category, String description) {
        this.slug = slug;
        this.displayName = displayName;
        this.category = category;
        this.description = description;
    }

    /** URL segment and the stable key the frontend stores in the user's layout, e.g. "pending-outlet-receives". */
    public String getSlug() {
        return slug;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    /** e.g. VIEW_DASHBOARD_PENDING_OUTLET_RECEIVES */
    public String getPermission() {
        return "VIEW_DASHBOARD_" + name();
    }

    /** Permission description, e.g. "Dashboard - Pending Outlet Receives". */
    public String getPermissionDescription() {
        return "Dashboard - " + displayName;
    }
}
