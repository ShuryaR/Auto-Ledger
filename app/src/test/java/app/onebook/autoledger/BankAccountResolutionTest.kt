package app.onebook.autoledger

import app.onebook.autoledger.core.AccountType
import app.onebook.autoledger.erp.ErpDocs
import app.onebook.autoledger.state.SetupLogic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankAccountResolutionTest {
    private val company = "Zen Pivot Pvt Ltd"

    private val ledgerAccounts = listOf(
        ErpDocs.LedgerAccountInfo("IDFC First - ZP", "Bank"),
        ErpDocs.LedgerAccountInfo("IDFC Credit Card - ZP", "Bank"),
        ErpDocs.LedgerAccountInfo("ICICI Bank - ZP", "Bank"),
        ErpDocs.LedgerAccountInfo("Cash - ZP", "Cash"),
        ErpDocs.LedgerAccountInfo("Debtors - ZP", "Receivable"),
    )

    @Test
    fun includesBankAccountsEvenWhenIsCompanyAccountIsUncheckedOrCompanyBlank() {
        val arr = JSONArray()
            .put(
                JSONObject()
                    .put("name", "IDFC First - Zen Pivot")
                    .put("account", "IDFC First - ZP")
                    .put("is_company_account", 1)
                    .put("bank", "IDFC First")
                    .put("company", company)
            )
            .put(
                JSONObject()
                    .put("name", "IDFC Credit Card - Zen Pivot")
                    .put("account", "IDFC Credit Card - ZP")
                    .put("is_company_account", 0)
                    .put("bank", "IDFC First")
                    .put("company", company)
            )
            .put(
                JSONObject()
                    .put("name", "ICICI Savings")
                    .put("account", "ICICI Bank - ZP")
                    .put("is_company_account", 0)
                    .put("bank", "ICICI")
                    .put("company", JSONObject.NULL)
            )
            .put(
                JSONObject()
                    .put("name", "Vendor Bank")
                    .put("account", JSONObject.NULL)
                    .put("is_company_account", 0)
                    .put("party_type", "Supplier")
            )
            .put(
                JSONObject()
                    .put("name", "Other Company Bank")
                    .put("account", "HDFC - Other")
                    .put("is_company_account", 1)
                    .put("company", "Other Co")
            )
            .put(
                JSONObject()
                    .put("name", "Other Company Unflagged")
                    .put("account", "Axis - Other")
                    .put("is_company_account", 0)
                    .put("company", JSONObject.NULL)
            )
            .put(
                JSONObject()
                    .put("name", "Closed IDFC Account")
                    .put("account", "IDFC First - ZP")
                    .put("is_company_account", 1)
                    .put("company", company)
                    .put("disabled", 1)
            )

        val parsed = ErpDocs.parseBankAccountRows(arr)
        val resolved = ErpDocs.resolveBankAccounts(parsed, ledgerAccounts, company)

        assertEquals(
            listOf("IDFC First - Zen Pivot", "IDFC Credit Card - Zen Pivot", "ICICI Savings"),
            resolved.map { it.name },
        )
    }

    @Test
    fun fallsBackToBankLedgerAccountsNotCoveredByBankAccountDoctype() {
        val resolved = ErpDocs.resolveBankAccounts(
            bankRows = emptyList(),
            ledgerAccounts = ledgerAccounts,
            company = company,
        )

        assertEquals(
            listOf("IDFC First - ZP", "IDFC Credit Card - ZP", "ICICI Bank - ZP"),
            resolved.map { it.name },
        )
        assertTrue(resolved.all { it.glAccount == it.name })
    }

    @Test
    fun doesNotDuplicateBankGlAccountAlreadyLinkedToBankAccountDoc() {
        val bankRows = listOf(
            ErpDocs.BankAccountInfo(
                name = "IDFC First - Zen Pivot",
                glAccount = "IDFC First - ZP",
                bankAccountNo = null,
                isCompanyAccount = false,
                bank = "IDFC First",
                company = company,
            )
        )
        val resolved = ErpDocs.resolveBankAccounts(bankRows, ledgerAccounts, company)
        assertEquals(
            listOf("IDFC First - Zen Pivot", "IDFC Credit Card - ZP", "ICICI Bank - ZP"),
            resolved.map { it.name },
        )
    }

    @Test
    fun setupLogicValidatesBlankGlAccount() {
        val modes = listOf(ErpDocs.ModeOfPaymentInfo("Wire Transfer", "Bank"))
        val drafts = SetupLogic.buildDrafts(
            accounts = listOf(
                ErpDocs.BankAccountInfo(
                    name = "IDFC Unlinked",
                    glAccount = "",
                    bankAccountNo = null,
                    isCompanyAccount = false,
                    bank = "IDFC First",
                    company = company,
                )
            ),
            existing = emptyList(),
            modes = modes,
        )
        assertTrue(drafts[0].supported)
        val selected = drafts.map {
            it.copy(selected = true, last4 = "1234", senders = "IDFCFB", kind = AccountType.BANK)
        }
        val res = SetupLogic.validateDrafts(selected)
        assertFalse(res.none)
        assertTrue(res.errors.containsKey("IDFC Unlinked"))
    }
}
