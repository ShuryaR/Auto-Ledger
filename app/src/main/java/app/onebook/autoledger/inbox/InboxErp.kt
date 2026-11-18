package app.onebook.autoledger.inbox

import app.onebook.autoledger.core.OpenInvoice
import app.onebook.autoledger.core.OpenOrder
import app.onebook.autoledger.core.PartyDoctype
import app.onebook.autoledger.erp.ErpAuthError
import app.onebook.autoledger.erp.ErpClient
import app.onebook.autoledger.erp.ErpDocs
import app.onebook.autoledger.erp.ErpOps
import app.onebook.autoledger.erp.makeErpOps
import app.onebook.autoledger.ingest.DrainErp

interface InboxErp : DrainErp {
    suspend fun getRequiredFields(doctype: PartyDoctype): List<ErpDocs.RequiredFieldInfo>
    suspend fun listOpenInvoices(partyType: PartyDoctype, party: String): List<OpenInvoice>
    suspend fun listOpenSalesOrders(customer: String): List<OpenOrder>
    suspend fun listLedgerAccounts(company: String, rootType: String? = null): List<ErpDocs.LedgerAccountInfo>
    suspend fun listBankAccounts(company: String): List<ErpDocs.BankAccountInfo>
}

fun makeInboxErp(client: ErpClient): InboxErp {
    val ops: ErpOps = makeErpOps(client)
    return object : InboxErp, ErpOps by ops {
        override suspend fun checkSession(): Boolean {
            return try {
                client.getObject("/api/method/frappe.auth.get_logged_user")
                true
            } catch (_: ErpAuthError) {
                false
            }
        }

        override suspend fun getRequiredFields(doctype: PartyDoctype): List<ErpDocs.RequiredFieldInfo> =
            ErpDocs.getRequiredFields(client, doctype.wire)

        override suspend fun listOpenInvoices(partyType: PartyDoctype, party: String): List<OpenInvoice> =
            ErpDocs.listOpenInvoices(client, partyType, party)

        override suspend fun listOpenSalesOrders(customer: String): List<OpenOrder> =
            ErpDocs.listOpenSalesOrders(client, customer)

        override suspend fun listLedgerAccounts(company: String, rootType: String?): List<ErpDocs.LedgerAccountInfo> =
            ErpDocs.listLedgerAccounts(client, company, rootType)

        override suspend fun listBankAccounts(company: String): List<ErpDocs.BankAccountInfo> =
            ErpDocs.listBankAccounts(client, company)
    }
}
