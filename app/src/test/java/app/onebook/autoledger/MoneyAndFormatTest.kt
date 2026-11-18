package app.onebook.autoledger

import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.Money
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.TxnKind
import app.onebook.autoledger.inbox.FlowLogic
import app.onebook.autoledger.inbox.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class MoneyAndFormatTest {
    @Test
    fun parseMinorExactIntegers() {
        assertEquals(51293276L, Money.parseMinor("INR 5,12,932.76"))
        assertEquals(11500000L, Money.parseMinor("Rs. 115,000.00"))
        assertEquals(10050L, Money.parseMinor("USD 100.5"))
        assertEquals(4200L, Money.parseMinor("42"))
    }

    @Test
    fun formatInrUsesIndianGroupingAndTrueMinus() {
        assertEquals("\u20B95,12,932.76", Money.formatInr(51293276L))
        assertEquals("\u2212\u20B95,12,932.76", Money.formatInr(-51293276L))
        assertEquals("USD 1,234.50", Money.formatCurrency(123450L, "USD"))
    }

    @Test
    fun minorToMajorDecimalPreservesPrecision() {
        assertEquals(BigDecimal("8512.40"), Money.minorToMajorDecimal(851240L))
        assertEquals(BigDecimal("0.05"), Money.minorToMajorDecimal(5L))
    }

    @Test
    fun parseRupeesRejectsZeroAndInvalid() {
        assertEquals(851240L, FlowLogic.parseRupees("₹8,512.40"))
        assertEquals(10000L, FlowLogic.parseRupees("100"))
        assertNull(FlowLogic.parseRupees("0"))
        assertNull(FlowLogic.parseRupees("-50"))
        assertNull(FlowLogic.parseRupees("12.345"))
    }

    @Test
    fun formattersUseExpectedDateAndSign() {
        assertEquals("8 Nov 2026", Formatters.longDate("2026-11-08"))
        val txn = PendingTxn(
            id = "t1",
            kind = TxnKind.CA_DEBIT,
            direction = Direction.OUT,
            amountMinor = 45000L,
            currency = "INR",
            occurredOn = "2026-11-08",
            accountType = AccountType.BANK,
            sender = "IDFCFB",
            receivedAt = 1_700_000_000_000L,
            rawHash = "h1",
        )
        assertEquals("\u2212\u20B9450.00", Formatters.signedAmount(txn))
    }
}
