package app.onebook.autoledger

import app.onebook.autoledger.core.AccountMapping
import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.core.ActionChoice
import app.onebook.autoledger.core.ActionType
import app.onebook.autoledger.core.CreatedDoc
import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.PendingTxn
import app.onebook.autoledger.core.SuggestEngine
import app.onebook.autoledger.core.TxnKind
import app.onebook.autoledger.core.TxnState
import app.onebook.autoledger.home.HomeInputs
import app.onebook.autoledger.home.HomeLogic
import app.onebook.autoledger.sms.ParseResult
import app.onebook.autoledger.sms.TemplateEngine
import app.onebook.autoledger.state.ActivityModel
import app.onebook.autoledger.state.RecencyTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TemplateEngineAndSuggestTest {
    @Test
    fun bundledIdfcTemplatesPassSelfAndSmokeCheck() {
        assertTrue(TemplateEngine.selfTest())
        val candidates = listOf(
            File("src/main/assets/templates/idfc.json"),
            File("app/src/main/assets/templates/idfc.json"),
        )
        val assetFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Bundled idfc.json asset not found in test working dir", assetFile)
        val engine = TemplateEngine.fromJson(assetFile!!.readText())
        assertNotNull(engine)
        assertTrue(TemplateEngine.smokeCheck(engine!!))

        val otpRes = engine.parseFull("AD-IDFCFB-S", "458192 is your OTP for payment of INR 500.00", 1_762_600_000_000L)
        assertTrue(otpRes is ParseResult.Drop && otpRes.reason == "otp")

        val debitRes = engine.parseFull(
            "AD-IDFCFB-S",
            "Your A/C XXXXX123456 is debited by INR 1,250.00 on 08/11/26 10:15. New bal: INR 48,750.00",
            1_762_600_000_000L,
        )
        assertTrue(debitRes is ParseResult.Ok)
        val txn = (debitRes as ParseResult.Ok).txn
        assertEquals(125000L, txn.amountMinor)
        assertEquals(4875000L, txn.balanceMinor)
        assertEquals(Direction.OUT, txn.direction)
        assertEquals(TxnKind.CA_DEBIT, txn.kind)
    }

    @Test
    fun learnRuleAndSuggestPrioritizesRuleMatch() {
        val txn = PendingTxn(
            id = "t1",
            kind = TxnKind.CARD_SPEND,
            direction = Direction.OUT,
            amountMinor = 75000L,
            currency = "INR",
            occurredOn = "2026-11-08",
            accountType = AccountType.CARD,
            accountLast4 = "9911",
            merchant = "SWIGGY",
            sender = "IDFCFB",
            receivedAt = 1_762_600_000_000L,
            rawHash = "rh1",
        )
        val learned = SuggestEngine.learnRule(txn, ActionChoice.Expense("Meals & Tea - OB"), emptyList())
        assertEquals(1, learned.size)
        assertEquals("m:out:swiggy", learned[0].matchKey)

        val suggestions = SuggestEngine.suggest(txn, learned, emptyList(), emptyList())
        assertEquals(1, suggestions.size)
        assertEquals(ActionType.EXPENSE, suggestions[0].choice.type)
        assertEquals("Meals & Tea - OB", suggestions[0].choice.expenseAccount)
    }

    @Test
    fun homeAndActivityModelsSummarizeCorrectly() {
        val bank = AccountMapping(
            bankAccount = "IDFC Current - OB",
            glAccount = "IDFC Bank - OB",
            kind = AccountType.BANK,
            senderIds = listOf("IDFCFB"),
            last4 = listOf("3455"),
            modeOfPayment = "Wire Transfer",
        )
        val created = PendingTxn(
            id = "t_created",
            kind = TxnKind.CA_CREDIT,
            direction = Direction.IN,
            amountMinor = 200000L,
            currency = "INR",
            occurredOn = "2026-11-08",
            accountType = AccountType.BANK,
            accountLast4 = "3455",
            counterparty = "Acme",
            sender = "IDFCFB",
            receivedAt = 1_762_600_000_000L,
            rawHash = "r1",
            state = TxnState.CREATED,
            createdDocs = listOf(CreatedDoc("Payment Entry", "ACC-PAY-001", docstatus = 0)),
        )
        val ignored = created.copy(id = "t_ignored", state = TxnState.IGNORED, createdDocs = emptyList())
        val totals = ActivityModel.totalsOf(listOf(created, ignored))
        assertEquals(200000L, totals.inMinor)
        assertEquals(0L, totals.outMinor)

        val groups = ActivityModel.groupByRecency(listOf(created), "2026-11-08")
        assertEquals(1, groups.size)
        assertEquals(RecencyTitle.TODAY, groups[0].title)

        val home = HomeLogic.buildHomeModel(
            HomeInputs(
                mappings = listOf(bank),
                pending = listOf(created),
                now = 1_762_600_000_000L,
            )
        )
        assertEquals(1, home.drafts.count)
        assertEquals(200000L, home.drafts.inMinor)
    }
}
