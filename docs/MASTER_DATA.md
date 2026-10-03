# Master Data

Data in Norbiz is divided into two main categories: Master and Transactional (see `docs/TRANSACTIONS.md` for the latter). Master data acts as the foundational building block of the system — representing the core entities, places, and things your business interacts with. Master Data includes Users, Roles, Permissions, Items, Brands, Company, and so on.

## Users
- Users are the primary actors of Norbiz
- `User` may belong to one or many companies via `user_companies` join table.
- A user can be assigned multiple roles. All permissions across all roles will be unionized in additive fashion granted to the user
- There is a special role `SUPER_ADMIN` that automatically granted ALL permissions
- Services verify the acting user's companies intersect the target resource's company. `SUPER_ADMIN` role bypasses all company checks.
- If a `SUPER_ADMIN` needs to create or update an entity scoped by a company, any controller through its request payload must require it to supply the `Company`id

## Employees
- Employees are people who actually work for the company. 
- Some employees are `Users`. Because some actions are made by people who does not access to Norbiz, their actions are delegated to the `Users`. For example, sales agents in retails outlets
- Employees can be assigned to many tags. Tags are: Agent (Will expand tags more in the future). There is an intermediary table `employee_tags` to enforce zero to many tag relationships

## Items
- Items are goods and services that a company offers and manages
- There are multiple ItemPrice types per Item. Cost price is information sensitive and thus the need for security management. This should not be viewed by users without the correct permission 
- If a user does not have a permission to view the Item's cost price and has access to creating and updating an Item, Norbiz should not allow any changes to the current Cost Price. Set Cost Price to 0 if it is a new Item
- Items can have multiple ItemTags. There is an intermediary table `item_tags` to enforce zero to many tag relationships. 
  Tags are: 
  - INVENTORY: marks that the item inventory physical count is counted and posted during Inventory Movement transactions
- `PUT /items/{id}` deletes all existing SKUs and prices then re-inserts from the request. `entityManager.flush()` is called before re-insertion to release unique constraints within the same transaction.
- An Item may belong to at most one `ItemGroup` (optional, `items.item_group_id`). Groups carry a Name, Description, BN Initials, and two percentage rates (0–100, 2 dp): Commission Rate and Focal Commission Rate. An inactive group can't be newly assigned to an item (items already in it keep it), and a group can't be deleted while any item is still assigned to it.
- `ItemSku.skuCode` is globally unique across all companies; `Item.itemCode` is unique per company; `Brand.name` is unique per company; `ItemCategory.name` is unique per company; `ItemGroup.name` is unique per company.

## Warehouses
- Warehouses are where the `Items` are stored

## Suppliers
- These are entities where we buy goods or avail services

## Customers
- Customers are entities we sell our goods or Outlets where we consign our products for selling
- There are two Customers: Customers (Direct buyers of products) and Outlets (Branches where we deliver our goods for selling)
- Outlets maintain their own inventory count. So it is important the inventory side-by-side with the warehouses our main warehouse monitor
