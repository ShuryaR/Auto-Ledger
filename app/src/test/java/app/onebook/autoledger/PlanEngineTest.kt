package app.onebook.autoledger

import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.core.ActionChoice
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.InvoiceAllocation
import app.onebook.autoledger.core.OrderAllocation
import app.onebook.autoledger.core.PartyDoctype
import app.onebook.autoledger.core.PartyRef
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.PlanContext
import app.onebook.autoledger.core.PlanEngine
import app.onebook.autoledger.core.PlanError
import app.onebook.autoledger.core.TxnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanEngineTest {
    private val bankAcc = AccountMapping(
        bankAccount = "IDFC Current - OB",
        glAccount = "IDFC Bank - OB",
        kind = AccountType.BANK,
        senderIds = listOf("IDFCFB"),
        last4 = listOf("3455"),
        modeOfPayment = "Wire Transfer",
    )

    private val secondBank = AccountMapping(
        bankAccount = "IDFC Savings - OB",
        glAccount = "IDFC Savings GL - OB",
        kind = AccountType.BANK,
        senderIds = listOf("IDFCFB"),
        last4 = listOf("8129"),
        modeOfPayment = "Wire Transfer",
    )

    private val ctx = PlanContext(
        company = "OneBook",
        account = bankAcc,
        drawingsAccount = "Drawings - OB",
        today = "2026-11-10",
    )

    private fun baseTxn(
        direction: Direction = Direction.OUT,
        currency: String = "INR",
        amountMinor: Long = 100000L,
        utr: String? = "UTR123456",
    ) = PendingTxn(
        id = "t_1",
        kind = if (direction == Direction.IN) TxnKind.CA_CREDIT else TxnKind.CA_DEBIT,
        direction = direction,
        amountMinor = amountMinor,
        currency = currency,
        occurredOn = "2026-11-08",
        accountType = AccountType.BANK,
        accountLast4 = "3455",
        counterparty = "Acme Corp",
        utr = utr,
        sender = "IDFCFB",
        receivedAt = 1_762_600_000_000L,
        rawHash = "hash1",
    )

    @Test
    fun foreignCurrencyWithoutInrNeedsAmount() {
        val plan = PlanEngine.planAction(
            baseTxn(direction = Direction.OUT, currency = "USD", amountMinor = 2000L),
            ActionChoice.Expense("Software - OB"),
            ctx,
        )
        assertTrue(plan.needsInrAmount)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun customerAdvanceWithOrderCreatesDraftPaymentEntry() {
        val plan = PlanEngine.planAction(
            baseTxn(direction = Direction.IN, amountMinor = 50000L),
            ActionChoice.CustomerAdvance(
                party = PartyRef(PartyDoctype.CUSTOMER, "Blue Foods"),
                orders = listOf(OrderAllocation("SAL-ORD-2026-0001", 50000L)),
            ),
            ctx,
        )
        assertFalse(plan.needsInrAmount)
        assertEquals(1, plan.steps.size)
        val step = plan.steps[0]
        assertEquals("Payment Entry", step.doctype)
        val body = step.bodyObject()
        assertEquals("Receive", body.getString("payment_type"))
        assertEquals("Blue Foods", body.getString("party"))
        assertEquals("IDFC Bank - OB", body.getString("paid_to"))
        assertEquals("Sales Order", body.getJSONArray("references").getJSONObject(0).getString("reference_doctype"))
    }

    @Test
    fun expenseCreatesDraftJournalEntryWithOptionalParty() {
        val plan = PlanEngine.planAction(
            baseTxn(direction = Direction.OUT, amountMinor = 45000L, utr = null),
            ActionChoice.Expense("Travel - OB", PartyRef(PartyDoctype.SUPPLIER, "IndiGo")),
            ctx,
        )
        assertEquals(1, plan.steps.size)
        val step = plan.steps[0]
        assertEquals("Journal Entry", step.doctype)
        val body = step.bodyObject()
        assertEquals("AL-hash1", body.getString("cheque_no"))
        val rows = body.getJSONArray("accounts")
        assertEquals("Travel - OB", rows.getJSONObject(0).getString("account"))
        assertEquals("IndiGo", rows.getJSONObject(0).getString("party"))
        assertEquals("IDFC Bank - OB", rows.getJSONObject(1).getString("account"))
    }

    @Test(expected = PlanError::class)
    fun sameAccountTransferThrows() {
        PlanEngine.planAction(
            baseTxn(direction = Direction.OUT),
            ActionChoice.Transfer.Outbound(bankAcc),
            ctx,
        )
    }

    @Test
    fun outboundTransferCreatesInternalTransferWithTarget() {
        val plan = PlanEngine.planAction(
            baseTxn(direction = Direction.OUT),
            ActionChoice.Transfer.Outbound(secondBank),
            ctx,
        )
        val body = plan.steps[0].bodyObject()
        assertEquals("Internal Transfer", body.getString("payment_type"))
        assertEquals("IDFC Bank - OB", body.getString("paid_from"))
        assertEquals("IDFC Savings GL - OB", body.getString("paid_to"))
    }

    @Test
    fun againstInvoiceBuildsPaymentReferences() {
        val plan = PlanEngine.planAction(
            baseTxn(direction = Direction.IN, amountMinor = 150000L),
            ActionChoice.AgainstInvoice(
                party = PartyRef(PartyDoctype.CUSTOMER, "FreshMart"),
                invoices = listOf(InvoiceAllocation("Sales Invoice", "SINV-001", 150000L)),
            ),
            ctx,
        )
        val body = plan.steps[0].bodyObject()
        assertEquals("Receive", body.getString("payment_type"))
        assertEquals("SINV-001", body.getJSONArray("references").getJSONObject(0).getString("reference_name"))
    }
}
