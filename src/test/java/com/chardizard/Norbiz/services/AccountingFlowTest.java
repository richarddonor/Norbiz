package com.chardizard.Norbiz.services;

import com.chardizard.Norbiz.dto.*;
import com.chardizard.Norbiz.exceptions.InsufficientStockException;
import com.chardizard.Norbiz.models.*;
import com.chardizard.Norbiz.repositories.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// AR/AP subledger flows (docs/ACCOUNTING.md): invoices open items, payments/memos/applications/refunds allocate them,
// checks deposit/clear/bounce/cancel, bank reconciliation, and the as-of reports. Runs against the configured Postgres;
// every test rolls back. TransactionReferenceService is mocked for the same reason as in TransactionEventServiceTest.
@SpringBootTest
@Transactional
class AccountingFlowTest {

    @Autowired AccountingSettingsService settingsService;
    @Autowired SalesInvoiceService salesInvoiceService;
    @Autowired PurchaseInvoiceService purchaseInvoiceService;
    @Autowired DeliveryReceiptService deliveryReceiptService;
    @Autowired CustomerMemoService customerMemoService;
    @Autowired SupplierMemoService supplierMemoService;
    @Autowired CustomerPaymentService customerPaymentService;
    @Autowired SupplierPaymentService supplierPaymentService;
    @Autowired CustomerCreditApplicationService customerCreditApplicationService;
    @Autowired SupplierCreditApplicationService supplierCreditApplicationService;
    @Autowired CheckStatusService checkStatusService;
    @Autowired DepositService depositService;
    @Autowired BankReconciliationService bankReconciliationService;
    @Autowired RefundService refundService;
    @Autowired StatementOfAccountService statementService;
    @Autowired AccountingReportService reportService;
    @Autowired InventoryAdjustmentService inventoryAdjustmentService;
    @Autowired CustomerService customerService;
    @Autowired BankService bankService;
    @Autowired BankAccountService bankAccountService;
    @Autowired CompanyRepository companyRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired ItemCategoryRepository itemCategoryRepository;
    @Autowired ItemRepository itemRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired InventoryBalanceRepository inventoryBalanceRepository;
    @Autowired OpenItemRepository openItemRepository;
    @Autowired OpenItemAllocationRepository allocationRepository;
    @Autowired BankEntryRepository bankEntryRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean TransactionReferenceService transactionReferenceService;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private int referenceCounter = 0;

    private String user;
    private Company company;
    private Warehouse mainWarehouse;
    private Customer customer;
    private Customer outlet;
    private Supplier supplier;
    private Item item;
    private Bank bank;
    private BankAccount account;

    @BeforeEach
    void setUp() {
        when(transactionReferenceService.next(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2) + "-TEST-" + suffix + "-" + (++referenceCounter));

        company = new Company();
        company.setName("Accounting Co " + suffix);
        company = companyRepository.save(company);

        User u = new User();
        u.setUsername("acct_user_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(company));
        user = userRepository.save(u).getUsername();

        mainWarehouse = new Warehouse();
        mainWarehouse.setCompany(company);
        mainWarehouse.setCode("MAIN" + suffix);
        mainWarehouse.setName("Main");
        mainWarehouse.setMain(true);
        mainWarehouse = warehouseRepository.save(mainWarehouse);

        customer = customerService.create(customerRequest("CUS" + suffix, CustomerType.CUSTOMER, 30), user);
        outlet = customerService.create(customerRequest("OUT" + suffix, CustomerType.OUTLET, 0), user);

        supplier = new Supplier();
        supplier.setCompany(company);
        supplier.setCode("SUP" + suffix);
        supplier.setName("Supplier " + suffix);
        supplier.setTermDays(15);
        supplier = supplierRepository.save(supplier);

        ItemCategory category = new ItemCategory();
        category.setCompany(company);
        category.setName("Acct Category " + suffix);
        category = itemCategoryRepository.save(category);
        item = new Item();
        item.setCompany(company);
        item.setItemCategory(category);
        item.setItemCode("ITEM-" + suffix);
        item.setName("Item " + suffix);
        item.getTags().add(ItemTag.INVENTORY);
        item = itemRepository.save(item);
        stock("100");

        BankRequest bankRequest = new BankRequest();
        bankRequest.setCompanyId(company.getId());
        bankRequest.setCode("BPI" + suffix);
        bankRequest.setName("BPI");
        bank = bankService.create(bankRequest, user);
        BankAccountRequest accountRequest = new BankAccountRequest();
        accountRequest.setCompanyId(company.getId());
        accountRequest.setBankId(bank.getId());
        accountRequest.setName("BPI Checking");
        accountRequest.setAccountNumber("ACCT-" + suffix);
        accountRequest.setOpeningBalance(new BigDecimal("10000.00"));
        account = bankAccountService.create(accountRequest, user);

        startDate("2026-01-01");
    }

    @AfterEach
    void everyOpenItemMatchesItsAllocations() {
        // The stored amounts are a cache of the allocation ledger; after any flow they must agree.
        for (OpenItem item : openItemRepository.findAll((root, q, cb) -> cb.equal(root.get("company").get("id"), company.getId()))) {
            BigDecimal applied = BigDecimal.ZERO;
            BigDecimal pending = BigDecimal.ZERO;
            for (OpenItemAllocation a : allocationRepository.findTouching(List.of(item.getId()))) {
                if (a.isReversed()) continue;
                applied = applied.add(a.getAmount());
                if (a.isPending() && a.getChargeItem().getId().equals(item.getId())) pending = pending.add(a.getAmount());
            }
            assertThat(item.getAppliedAmount()).as("applied of " + item.getReferenceNumber()).isEqualByComparingTo(applied);
            assertThat(item.getPendingAmount()).as("pending of " + item.getReferenceNumber()).isEqualByComparingTo(pending);
            assertThat(item.getBalance()).isEqualByComparingTo(item.getAmount().subtract(item.getAppliedAmount()));
        }
    }

    // ---- settings / purchase invoices ----

    @Test
    void nothingPostsBeforeTheStartDateIsConfigured() {
        Company saved = companyRepository.save(newCompany("No settings " + suffix));
        String otherUser = user2(saved);
        CustomerMemoRequest req = memo(MemoType.DEBIT, CustomerMemoReason.BILLING, "2026-03-01", "100.00");
        req.setCompanyId(saved.getId());
        req.setCustomerId(customerService.create(customerRequestFor(saved, "C2" + suffix), otherUser).getId());
        assertThatThrownBy(() -> customerMemoService.create(req, otherUser))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("start date is not configured");
    }

    @Test
    void purchaseInvoiceStoresItsNetAndOpensAPayableOnlyFromTheStartDate() {
        PurchaseInvoice tracked = purchaseInvoice("2026-02-10", "10", "123.456", "10", "5.00");
        // 10 x 123.456 = 1234.56, less 10% = 1111.104, plus 5.00 fee = 1116.104 → 1116.10
        assertThat(tracked.getNetAmount()).isEqualByComparingTo("1116.10");
        assertThat(tracked.getOpenItem()).isNotNull();
        assertThat(tracked.getOpenItem().getBalance()).isEqualByComparingTo("1116.10");
        assertThat(tracked.getDueDate()).isEqualTo(tracked.getInvoiceDate().plusSeconds(15 * 86400L));

        PurchaseInvoice untracked = purchaseInvoice("2025-12-31", "1", "100", "0", null);
        assertThat(untracked.getOpenItem()).isNull();
    }

    @Test
    void startDateCanMoveUntilSomethingIsApplied() {
        PurchaseInvoice pi = purchaseInvoice("2026-02-10", "1", "100", "0", null);
        startDate("2026-03-01");
        assertThat(pi.getOpenItem()).isNull();
        startDate("2026-02-01");
        assertThat(pi.getOpenItem()).isNotNull();

        supplierPayment("2026-02-15", issuedCash("100.00"), apply(pi.getOpenItem(), "100.00"));
        assertThatThrownBy(() -> startDate("2026-01-15"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("can no longer be changed");
    }

    // ---- sales invoices ----

    @Test
    void directSalesInvoiceDeductsMainWarehouseStockAndVoidRestoresIt() {
        SalesInvoice si = directInvoice("2026-03-01", "10", "50.00");
        assertThat(si.getNetAmount()).isEqualByComparingTo("500.00");
        assertThat(si.getDueDate()).isEqualTo(si.getInvoiceDate().plusSeconds(30 * 86400L));
        assertThat(si.getOpenItem().getBalance()).isEqualByComparingTo("500.00");
        assertThat(stockOnHand()).isEqualByComparingTo("90");

        salesInvoiceService.voidSalesInvoice(si.getId(), user);
        assertThat(stockOnHand()).isEqualByComparingTo("100");
        assertThat(si.getOpenItem().getStatus()).isEqualTo(OpenItemStatus.VOIDED);
    }

    @Test
    void directSalesInvoiceNeedsStock() {
        assertThatThrownBy(() -> directInvoice("2026-03-01", "101", "1.00")).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void salesInvoiceBillsADeliveryReceiptOnceAndVoidingFreesIt() {
        DeliveryReceipt dr = deliveryReceipt(customer, "4", "25.00");
        SalesInvoiceRequest req = new SalesInvoiceRequest();
        req.setCompanyId(company.getId());
        req.setDeliveryReceiptId(dr.getId());
        req.setInvoiceDate("2026-03-05");
        SalesInvoice si = salesInvoiceService.create(req, user);

        assertThat(si.getNetAmount()).isEqualByComparingTo("100.00");
        assertThat(si.getCustomer().getId()).isEqualTo(customer.getId());
        assertThat(dr.isBilled()).isTrue();
        assertThat(stockOnHand()).as("the DR already moved the stock").isEqualByComparingTo("96");
        assertThatThrownBy(() -> salesInvoiceService.create(req, user)).hasMessageContaining("already billed");
        assertThatThrownBy(() -> deliveryReceiptService.voidDeliveryReceipt(dr.getId(), user)).hasMessageContaining("Sales Invoice");

        salesInvoiceService.voidSalesInvoice(si.getId(), user);
        assertThat(dr.isBilled()).isFalse();
        assertThat(salesInvoiceService.create(req, user).getNetAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void outletDeliveriesCantBeBilled() {
        DeliveryReceipt dr = deliveryReceipt(outlet, "1", "10.00");
        SalesInvoiceRequest req = new SalesInvoiceRequest();
        req.setCompanyId(company.getId());
        req.setDeliveryReceiptId(dr.getId());
        req.setInvoiceDate("2026-03-05");
        assertThatThrownBy(() -> salesInvoiceService.create(req, user)).hasMessageContaining("outlet");
    }

    // ---- customer payments, advances, credit applications ----

    @Test
    void partialPaymentsThenAnAdvanceAppliedToALaterInvoice() {
        SalesInvoice first = directInvoice("2026-03-01", "20", "50.00");   // 1000
        customerPayment("2026-03-10", List.of(cashTender("600.00")), apply(first.getOpenItem(), "600.00"));
        assertThat(first.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);

        CustomerPayment second = customerPayment("2026-03-20", List.of(cashTender("700.00")), apply(first.getOpenItem(), "400.00"));
        assertThat(first.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        OpenItem advance = second.getTenders().getFirst().getOpenItem();
        assertThat(advance.getBalance()).as("unapplied = advance").isEqualByComparingTo("300.00");

        SalesInvoice later = directInvoice("2026-04-01", "10", "50.00");   // 500
        CustomerCreditApplicationRequest cca = new CustomerCreditApplicationRequest();
        cca.setCompanyId(company.getId());
        cca.setCustomerId(customer.getId());
        cca.setApplicationDate("2026-04-02");
        cca.setLines(List.of(creditLine(advance, later.getOpenItem(), "300.00")));
        CustomerCreditApplication application = customerCreditApplicationService.create(cca, user);

        assertThat(later.getOpenItem().getBalance()).isEqualByComparingTo("200.00");
        assertThat(advance.getStatus()).isEqualTo(OpenItemStatus.CLOSED);
        assertThatThrownBy(() -> salesInvoiceService.voidSalesInvoice(later.getId(), user)).hasMessageContaining("applied");

        customerCreditApplicationService.voidApplication(application.getId(), user);
        assertThat(later.getOpenItem().getBalance()).isEqualByComparingTo("500.00");
        assertThat(advance.getBalance()).isEqualByComparingTo("300.00");
        // Cash still on hand and nothing later uses its credit any more: voidable, and the first invoice reopens.
        customerPaymentService.voidPayment(second.getId(), user);
        assertThat(first.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
    }

    @Test
    void applicationsCantExceedWhatIsPaidOrWhatIsOwed() {
        SalesInvoice si = directInvoice("2026-03-01", "2", "50.00");   // 100
        assertThatThrownBy(() -> customerPayment("2026-03-10", List.of(cashTender("50.00")), apply(si.getOpenItem(), "60.00")))
                .hasMessageContaining("exceed");
        assertThatThrownBy(() -> customerPayment("2026-03-10", List.of(cashTender("500.00")), apply(si.getOpenItem(), "150.00")))
                .hasMessageContaining("balance is 100.00");
    }

    @Test
    void bouncedCheckReopensTheInvoiceAndAReplacementSettlesIt() {
        SalesInvoice si = directInvoice("2026-03-01", "20", "50.00");   // 1000
        CustomerPayment payment = customerPayment("2026-03-10", List.of(checkTender("1000.00", "CHK-1", "2026-03-10")),
                apply(si.getOpenItem(), "1000.00"));
        CustomerPaymentTender check = payment.getTenders().getFirst();
        assertThat(si.getOpenItem().getPendingAmount()).as("PDC").isEqualByComparingTo("1000.00");
        assertThat(si.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);

        Deposit deposit = deposit("2026-03-11", check);
        assertThat(check.getStatus()).isEqualTo(ReceivedCheckStatus.DEPOSITED);
        assertThatThrownBy(() -> customerPaymentService.voidPayment(payment.getId(), user)).hasMessageContaining("DEPOSITED");

        checkStatusService.bounce(check.getId(), "2026-03-14", BounceReason.DAIF, null, user);
        assertThat(check.getStatus()).isEqualTo(ReceivedCheckStatus.BOUNCED);
        assertThat(si.getOpenItem().getBalance()).isEqualByComparingTo("1000.00");
        assertThat(si.getOpenItem().getBouncedAmount()).isEqualByComparingTo("1000.00");
        assertThat(si.getOpenItem().getPendingAmount()).isEqualByComparingTo("0");
        assertThat(si.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        assertThat(bankEntryRepository.findBySourceTypeAndSourceIdOrderByIdAsc(TransactionType.DEPOSIT, deposit.getId()))
                .extracting(BankEntry::getEntryType, BankEntry::getAmount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(BankEntryType.DEPOSIT, new BigDecimal("1000.00")),
                        org.assertj.core.groups.Tuple.tuple(BankEntryType.CHECK_RETURNED, new BigDecimal("-1000.00")));
        assertThatThrownBy(() -> depositService.voidDeposit(deposit.getId(), user)).hasMessageContaining("BOUNCED");

        ReceivedTenderRequest replacement = cashTender("1000.00");
        replacement.setReplacesTenderId(check.getId());
        customerPayment("2026-03-16", List.of(replacement), apply(si.getOpenItem(), "1000.00"));
        assertThat(check.getStatus()).isEqualTo(ReceivedCheckStatus.REPLACED);
        assertThat(si.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void postDatedChecksWaitForTheirDateBeforeDeposit() {
        CustomerPayment payment = customerPayment("2026-03-10", List.of(checkTender("100.00", "PDC-1", "2026-04-30")), null);
        CustomerPaymentTender check = payment.getTenders().getFirst();
        assertThatThrownBy(() -> deposit("2026-03-11", check)).hasMessageContaining("post-dated");
    }

    // ---- supplier payments / issued checks ----

    @Test
    void supplierCheckPaysTheInvoiceAndCancellingItReopensIt() {
        PurchaseInvoice pi = purchaseInvoice("2026-02-10", "10", "100", "0", null);   // 1000
        IssuedTenderRequest check = issuedCheck("1000.00", "OUR-1", "2026-02-20");
        SupplierPayment payment = supplierPayment("2026-02-20", check, apply(pi.getOpenItem(), "1000.00"));
        SupplierPaymentTender tender = payment.getTenders().getFirst();
        assertThat(pi.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(pi.getOpenItem().getPendingAmount()).isEqualByComparingTo("1000.00");
        assertThat(reportService.bankBook(account.getId(), "2026-02-01", "2026-02-28", user).getClosingBalance())
                .isEqualByComparingTo("9000.00");

        checkStatusService.cancel(tender.getId(), "2026-03-01", IssuedCancelReason.STALE, null, user);
        assertThat(tender.getStatus()).isEqualTo(IssuedCheckStatus.CANCELLED);
        assertThat(pi.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        assertThat(reportService.bankBook(account.getId(), "2026-02-01", "2026-03-31", user).getClosingBalance())
                .isEqualByComparingTo("10000.00");

        IssuedTenderRequest replacement = issuedCheck("1000.00", "OUR-2", "2026-03-02");
        replacement.setReplacesTenderId(tender.getId());
        supplierPayment("2026-03-02", replacement, apply(pi.getOpenItem(), "1000.00"));
        assertThat(pi.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThatThrownBy(() -> purchaseInvoiceService.voidPurchaseInvoice(pi.getId(), user)).hasMessageContaining("applied");
    }

    @Test
    void supplierDebitMemoLowersWhatWeOweAndAnAdvanceIsRefundedToUs() {
        PurchaseInvoice pi = purchaseInvoice("2026-02-10", "10", "100", "0", null);   // 1000
        SupplierMemoRequest sdm = new SupplierMemoRequest();
        sdm.setCompanyId(company.getId());
        sdm.setSupplierId(supplier.getId());
        sdm.setMemoType(MemoType.DEBIT);
        sdm.setReason(SupplierMemoReason.PURCHASE_RETURN);
        sdm.setMemoDate("2026-02-12");
        sdm.setLines(List.of(amountLine("Returned 2 pcs", "200.00")));
        sdm.setApplications(List.of(apply(pi.getOpenItem(), "200.00")));
        SupplierMemo memo = supplierMemoService.create(sdm, user);
        assertThat(memo.getOpenItem().getDirection()).isEqualTo(OpenItemDirection.CREDIT);
        assertThat(pi.getOpenItem().getBalance()).isEqualByComparingTo("800.00");

        SupplierPayment payment = supplierPayment("2026-02-20", transfer("1000.00"), apply(pi.getOpenItem(), "800.00"));
        OpenItem advance = payment.getTenders().getFirst().getOpenItem();
        assertThat(advance.getBalance()).isEqualByComparingTo("200.00");

        SupplierRefundRequest refund = new SupplierRefundRequest();
        refund.setCompanyId(company.getId());
        refund.setSupplierId(supplier.getId());
        refund.setRefundDate("2026-02-25");
        refund.setMode(ReceivedPaymentMode.BANK_TRANSFER);
        refund.setBankAccountId(account.getId());
        refund.setLines(List.of(refundLine(advance, "200.00")));
        SupplierRefund saved = refundService.createSupplierRefund(refund, user);
        assertThat(advance.getBalance()).isEqualByComparingTo("0");
        assertThat(reportService.bankBook(account.getId(), "2026-02-01", "2026-02-28", user).getClosingBalance())
                .as("10000 - 1000 transfer + 200 refund").isEqualByComparingTo("9200.00");

        refundService.voidSupplierRefund(saved.getId(), user);
        assertThat(advance.getBalance()).isEqualByComparingTo("200.00");
    }

    // ---- memos ----

    @Test
    void openingBalancesMustPredateTheStartDateAndOtherMemosMustNot() {
        CustomerMemoRequest opening = memo(MemoType.DEBIT, CustomerMemoReason.OPENING_BALANCE, "2025-12-31", "750.00");
        opening.setDueDate("2025-11-30");
        opening.setOriginalReference("CDM-510692");
        CustomerMemo memo = customerMemoService.create(opening, user);
        assertThat(memo.getOpenItem().getDueDate()).isEqualTo(java.time.Instant.parse("2025-11-30T00:00:00Z"));

        assertThatThrownBy(() -> customerMemoService.create(memo(MemoType.DEBIT, CustomerMemoReason.OPENING_BALANCE, "2026-01-05", "1.00"), user))
                .hasMessageContaining("before the accounting start date");
        assertThatThrownBy(() -> customerMemoService.create(memo(MemoType.DEBIT, CustomerMemoReason.BILLING, "2025-12-31", "1.00"), user))
                .hasMessageContaining("OPENING_BALANCE");
    }

    @Test
    void creditMemoAppliedInlineIsReversedWhenVoided() {
        SalesInvoice si = directInvoice("2026-03-01", "2", "50.00");   // 100
        CustomerMemoRequest ccm = memo(MemoType.CREDIT, CustomerMemoReason.REBATE, "2026-03-02", "30.00");
        ccm.setApplications(List.of(apply(si.getOpenItem(), "30.00")));
        CustomerMemo memo = customerMemoService.create(ccm, user);
        assertThat(si.getOpenItem().getBalance()).isEqualByComparingTo("70.00");

        customerMemoService.voidMemo(memo.getId(), user);
        assertThat(si.getOpenItem().getBalance()).isEqualByComparingTo("100.00");
        assertThat(memo.getOpenItem().getStatus()).isEqualTo(OpenItemStatus.VOIDED);
    }

    // ---- bank reconciliation ----

    @Test
    void reconciliationClearsDepositsAndChecksAndMustBalance() {
        SalesInvoice si = directInvoice("2026-03-01", "10", "50.00");   // 500
        CustomerPayment received = customerPayment("2026-03-10", List.of(checkTender("500.00", "CUST-1", "2026-03-10")),
                apply(si.getOpenItem(), "500.00"));
        CustomerPaymentTender customerCheck = received.getTenders().getFirst();
        Deposit deposit = deposit("2026-03-11", customerCheck);
        PurchaseInvoice pi = purchaseInvoice("2026-03-01", "3", "100", "0", null);   // 300
        SupplierPayment paid = supplierPayment("2026-03-12", issuedCheck("300.00", "OUR-9", "2026-03-12"), apply(pi.getOpenItem(), "300.00"));
        SupplierPaymentTender ourCheck = paid.getTenders().getFirst();

        var candidates = bankReconciliationService.candidates(account.getId(), "2026-03-31", user);
        assertThat(candidates.previousBalance()).isEqualByComparingTo("10000.00");
        assertThat(candidates.bookBalance()).isEqualByComparingTo("10200.00");
        List<Long> entryIds = candidates.entries().stream().map(BankEntry::getId).toList();
        assertThat(entryIds).hasSize(2);

        BankReconciliationRequest req = new BankReconciliationRequest();
        req.setBankAccountId(account.getId());
        req.setStatementDate("2026-03-31");
        req.setClearedEntryIds(entryIds);
        BankReconciliationAdjustmentRequest charge = new BankReconciliationAdjustmentRequest();
        charge.setType(BankAdjustmentType.BANK_CHARGE);
        charge.setAmount(new BigDecimal("-50.00"));
        charge.setDescription("Service charge");
        req.setAdjustments(List.of(charge));
        req.setStatementEndingBalance(new BigDecimal("10100.00"));
        assertThatThrownBy(() -> bankReconciliationService.create(req, user)).hasMessageContaining("difference -50.00");

        req.setStatementEndingBalance(new BigDecimal("10150.00"));
        BankReconciliation recon = bankReconciliationService.create(req, user);
        assertThat(recon.getBookBalance()).isEqualByComparingTo("10150.00");
        assertThat(recon.getOutstandingTotal()).isEqualByComparingTo("0");
        assertThat(customerCheck.getStatus()).isEqualTo(ReceivedCheckStatus.CLEARED);
        assertThat(ourCheck.getStatus()).isEqualTo(IssuedCheckStatus.CLEARED);
        assertThat(si.getOpenItem().getPendingAmount()).isEqualByComparingTo("0");
        assertThat(pi.getOpenItem().getPendingAmount()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> depositService.voidDeposit(deposit.getId(), user)).isInstanceOf(IllegalArgumentException.class);

        bankReconciliationService.voidReconciliation(recon.getId(), user);
        assertThat(customerCheck.getStatus()).isEqualTo(ReceivedCheckStatus.DEPOSITED);
        assertThat(ourCheck.getStatus()).isEqualTo(IssuedCheckStatus.ISSUED);
        assertThat(si.getOpenItem().getPendingAmount()).isEqualByComparingTo("500.00");
        assertThat(bankReconciliationService.candidates(account.getId(), "2026-03-31", user).bookBalance())
                .as("the bank charge is voided with it").isEqualByComparingTo("10200.00");
    }

    // ---- refunds / reports ----

    @Test
    void customerAdvanceRefundedByCheck() {
        CustomerPayment advance = customerPayment("2026-03-10", List.of(cashTender("500.00")), null);
        OpenItem credit = advance.getTenders().getFirst().getOpenItem();
        CustomerRefundRequest req = new CustomerRefundRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(customer.getId());
        req.setRefundDate("2026-03-15");
        req.setMode(IssuedPaymentMode.CHECK);
        req.setBankAccountId(account.getId());
        req.setCheckNumber("RF-1");
        req.setCheckDate("2026-03-15");
        req.setLines(List.of(refundLine(credit, "200.00")));
        CustomerRefund refund = refundService.createCustomerRefund(req, user);

        assertThat(credit.getBalance()).isEqualByComparingTo("300.00");
        assertThat(refund.getStatus()).isEqualTo(IssuedCheckStatus.ISSUED);
        assertThat(refund.getOpenItem().getStatus()).isEqualTo(OpenItemStatus.CLOSED);
        req.setLines(List.of(refundLine(credit, "400.00")));
        assertThatThrownBy(() -> refundService.createCustomerRefund(req, user)).hasMessageContaining("unapplied");
    }

    @Test
    void agingStatementAndLedgerAgreeAsOfADate() {
        SalesInvoice old = directInvoice("2026-01-10", "10", "100.00");    // 1000, due 2026-02-09
        SalesInvoice recent = directInvoice("2026-04-01", "2", "100.00");  // 200, due 2026-05-01
        customerPayment("2026-04-05", List.of(cashTender("700.00")), apply(old.getOpenItem(), "400.00"));   // 300 advance left

        AgingReportResponse aging = reportService.aging(AccountSide.RECEIVABLE, company.getId(), "2026-04-10", AgingBasis.DUE_DATE,
                customer.getId(), true, user);
        AgingRowResponse row = aging.getRows().getFirst();
        assertThat(row.getCurrent()).isEqualByComparingTo("200.00");
        assertThat(row.getDays31To60()).as("60 days past 2026-02-09").isEqualByComparingTo("600.00");
        assertThat(row.getUnappliedCredits()).isEqualByComparingTo("300.00");
        assertThat(row.getNetBalance()).isEqualByComparingTo("500.00");

        AgingRowResponse before = reportService.aging(AccountSide.RECEIVABLE, company.getId(), "2026-03-01", AgingBasis.DUE_DATE,
                customer.getId(), false, user).getRows().getFirst();
        assertThat(before.getTotalDue()).as("before the payment and the second invoice").isEqualByComparingTo("1000.00");

        StatementOfAccountRequest soa = new StatementOfAccountRequest();
        soa.setCompanyId(company.getId());
        soa.setCustomerId(customer.getId());
        soa.setStatementDate("2026-04-10");
        StatementOfAccount statement = statementService.create(soa, user);
        assertThat(statement.getTotalBalance()).isEqualByComparingTo("500.00");
        assertThat(statement.getLines()).hasSize(3);

        LedgerResponse ledger = reportService.ledger(AccountSide.RECEIVABLE, company.getId(), customer.getId(), "2026-03-01", "2026-04-30", user);
        assertThat(ledger.getOpeningBalance()).isEqualByComparingTo("1000.00");
        assertThat(ledger.getClosingBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void anotherCompanysItemsCantBeApplied() {
        Company other = companyRepository.save(newCompany("Other " + suffix));
        OpenItem foreign = new OpenItem();
        foreign.setCompany(other);
        foreign.setSide(AccountSide.RECEIVABLE);
        foreign.setCustomer(customerService.create(customerRequestFor(other, "X" + suffix), user2(other)));
        foreign.setDirection(OpenItemDirection.CHARGE);
        foreign.setSourceType(OpenItemSourceType.CUSTOMER_DEBIT_MEMO);
        foreign.setSourceId(-1L);
        foreign.setReferenceNumber("FOREIGN");
        foreign.setDocumentDate(java.time.Instant.parse("2026-03-01T00:00:00Z"));
        foreign.setAmount(new BigDecimal("10.00"));
        foreign.setBalance(new BigDecimal("10.00"));
        foreign.setCreatedAt(java.time.Instant.now());
        OpenItem saved = openItemRepository.save(foreign);
        assertThatThrownBy(() -> customerPayment("2026-03-10", List.of(cashTender("10.00")), apply(saved, "10.00")))
                .hasMessageContaining("does not belong to company");
    }

    // ---- helpers ----

    private String user2(Company other) {
        User u = new User();
        u.setUsername("acct_other_" + suffix);
        u.setEmail(u.getUsername() + "@test.local");
        u.setPassword("x");
        u.setCompanies(Set.of(other));
        return userRepository.save(u).getUsername();
    }

    private static Company newCompany(String name) {
        Company c = new Company();
        c.setName(name);
        return c;
    }

    private void startDate(String date) {
        AccountingSettingsRequest req = new AccountingSettingsRequest();
        req.setCompanyId(company.getId());
        req.setStartDate(date);
        settingsService.save(req, user);
    }

    private void stock(String quantity) {
        InventoryAdjustmentLineRequest line = new InventoryAdjustmentLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        InventoryAdjustmentRequest req = new InventoryAdjustmentRequest();
        req.setCompanyId(company.getId());
        req.setWarehouseId(mainWarehouse.getId());
        req.setAdjustmentDate("2025-12-01");
        req.setLines(List.of(line));
        inventoryAdjustmentService.create(req, user);
    }

    private BigDecimal stockOnHand() {
        return inventoryBalanceRepository.findByItemIdAndWarehouseId(item.getId(), mainWarehouse.getId()).orElseThrow().getQuantity();
    }

    private CustomerRequest customerRequest(String code, CustomerType type, int termDays) {
        CustomerRequest req = customerRequestFor(company, code);
        req.setType(type);
        req.setTermDays(termDays);
        return req;
    }

    private static CustomerRequest customerRequestFor(Company c, String code) {
        CustomerRequest req = new CustomerRequest();
        req.setCompanyId(c.getId());
        req.setCode(code);
        req.setName("Name " + code);
        req.setType(CustomerType.CUSTOMER);
        return req;
    }

    private SalesInvoice directInvoice(String date, String quantity, String unitPrice) {
        SalesInvoiceLineRequest line = new SalesInvoiceLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        line.setUnitPrice(new BigDecimal(unitPrice));
        SalesInvoiceRequest req = new SalesInvoiceRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(customer.getId());
        req.setInvoiceDate(date);
        req.setLines(List.of(line));
        return salesInvoiceService.create(req, user);
    }

    private DeliveryReceipt deliveryReceipt(Customer to, String quantity, String unitPrice) {
        DeliveryReceiptLineRequest line = new DeliveryReceiptLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        line.setUnitPrice(new BigDecimal(unitPrice));
        DeliveryReceiptRequest req = new DeliveryReceiptRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(to.getId());
        req.setDeliveryDate("2026-03-01");
        req.setLines(List.of(line));
        return deliveryReceiptService.create(req, user);
    }

    private PurchaseInvoice purchaseInvoice(String date, String quantity, String cost, String headerDiscount, String fee) {
        PurchaseInvoiceLineRequest line = new PurchaseInvoiceLineRequest();
        line.setItemId(item.getId());
        line.setQuantity(new BigDecimal(quantity));
        line.setCostPrice(new BigDecimal(cost));
        PurchaseInvoiceRequest req = new PurchaseInvoiceRequest();
        req.setCompanyId(company.getId());
        req.setWarehouseId(mainWarehouse.getId());
        req.setSupplierId(supplier.getId());
        req.setInvoiceDate(date);
        req.setDiscountPercentage(new BigDecimal(headerDiscount));
        req.setLines(List.of(line));
        if (fee != null) {
            PurchaseInvoiceFeeRequest f = new PurchaseInvoiceFeeRequest();
            f.setDescription("Freight");
            f.setAmount(new BigDecimal(fee));
            req.setFees(List.of(f));
        }
        return purchaseInvoiceService.create(req, user, true);
    }

    private CustomerMemoRequest memo(MemoType type, CustomerMemoReason reason, String date, String amount) {
        CustomerMemoRequest req = new CustomerMemoRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(customer.getId());
        req.setMemoType(type);
        req.setReason(reason);
        req.setMemoDate(date);
        req.setLines(List.of(amountLine("Memo line", amount)));
        return req;
    }

    private static AmountLineRequest amountLine(String description, String amount) {
        AmountLineRequest line = new AmountLineRequest();
        line.setDescription(description);
        line.setAmount(new BigDecimal(amount));
        return line;
    }

    private CustomerPayment customerPayment(String date, List<ReceivedTenderRequest> tenders, ApplicationRequest application) {
        CustomerPaymentRequest req = new CustomerPaymentRequest();
        req.setCompanyId(company.getId());
        req.setCustomerId(customer.getId());
        req.setPaymentDate(date);
        req.setTenders(new ArrayList<>(tenders));
        req.setApplications(application != null ? List.of(application) : null);
        return customerPaymentService.create(req, user);
    }

    private SupplierPayment supplierPayment(String date, IssuedTenderRequest tender, ApplicationRequest application) {
        SupplierPaymentRequest req = new SupplierPaymentRequest();
        req.setCompanyId(company.getId());
        req.setSupplierId(supplier.getId());
        req.setPaymentDate(date);
        req.setTenders(List.of(tender));
        req.setApplications(application != null ? List.of(application) : null);
        return supplierPaymentService.create(req, user);
    }

    private static ReceivedTenderRequest cashTender(String amount) {
        ReceivedTenderRequest t = new ReceivedTenderRequest();
        t.setMode(ReceivedPaymentMode.CASH);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    private ReceivedTenderRequest checkTender(String amount, String number, String checkDate) {
        ReceivedTenderRequest t = new ReceivedTenderRequest();
        t.setMode(ReceivedPaymentMode.CHECK);
        t.setAmount(new BigDecimal(amount));
        t.setCheckNumber(number);
        t.setCheckDate(checkDate);
        t.setBankId(bank.getId());
        return t;
    }

    private IssuedTenderRequest issuedCheck(String amount, String number, String checkDate) {
        IssuedTenderRequest t = new IssuedTenderRequest();
        t.setMode(IssuedPaymentMode.CHECK);
        t.setAmount(new BigDecimal(amount));
        t.setBankAccountId(account.getId());
        t.setCheckNumber(number);
        t.setCheckDate(checkDate);
        return t;
    }

    private static IssuedTenderRequest issuedCash(String amount) {
        IssuedTenderRequest t = new IssuedTenderRequest();
        t.setMode(IssuedPaymentMode.CASH);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    private IssuedTenderRequest transfer(String amount) {
        IssuedTenderRequest t = new IssuedTenderRequest();
        t.setMode(IssuedPaymentMode.BANK_TRANSFER);
        t.setAmount(new BigDecimal(amount));
        t.setBankAccountId(account.getId());
        return t;
    }

    private static ApplicationRequest apply(OpenItem charge, String amount) {
        ApplicationRequest a = new ApplicationRequest();
        a.setOpenItemId(charge.getId());
        a.setAmount(new BigDecimal(amount));
        return a;
    }

    private static CreditApplicationLineRequest creditLine(OpenItem credit, OpenItem charge, String amount) {
        CreditApplicationLineRequest l = new CreditApplicationLineRequest();
        l.setCreditItemId(credit.getId());
        l.setChargeItemId(charge.getId());
        l.setAmount(new BigDecimal(amount));
        return l;
    }

    private static RefundLineRequest refundLine(OpenItem credit, String amount) {
        RefundLineRequest l = new RefundLineRequest();
        l.setCreditItemId(credit.getId());
        l.setAmount(new BigDecimal(amount));
        return l;
    }

    private Deposit deposit(String date, CustomerPaymentTender... tenders) {
        DepositRequest req = new DepositRequest();
        req.setCompanyId(company.getId());
        req.setBankAccountId(account.getId());
        req.setDepositDate(date);
        req.setTenderIds(java.util.Arrays.stream(tenders).map(CustomerPaymentTender::getId).toList());
        return depositService.create(req, user);
    }
}
