package app.onebook.autoledger.erp

import app.onebook.autoledger.core.Direction
import app.onebook.autoledger.core.OpenInvoice
import app.onebook.autoledger.core.OpenOrder
import app.onebook.autoledger.core.PartyDoctype
import app.onebook.autoledger.core.PartyRef
import app.onebook.autoledger.core.SimilarEngine
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

object ErpDocs {
    private fun resource(doctype: String) = "/api/resource/${ErpHttp.encodeSegment(doctype)}"

    private suspend fun listRows(c: ErpClient, doctype: String, params: Map<String, Any?>): JSONArray {
        val r = c.getObject(resource(doctype), params)
        return r.optJSONArray("data") ?: throw ErpHttp.unexpectedResponse()
    }

    data class CompanyInfo(val name: String, val currency: String)
    data class BankAccountInfo(
        val name: String,
        val glAccount: String,
        val bankAccountNo: String?,
        val isCompanyAccount: Boolean,
        val bank: String?,
        val company: String? = null,
        val partyType: String? = null,
        val disabled: Boolean = false,
    )
    data class LedgerAccountInfo(val name: String, val accountType: String?)
    data class ModeOfPaymentInfo(val name: String, val type: String?)
    data class PartySearchHit(val ref: PartyRef, val displayName: String)
    data class RequiredFieldInfo(val fieldname: String, val label: String, val fieldtype: String)
    data class UtrHit(val doctype: String, val name: String, val docstatus: Int)
    data class DraftAccountRow(
        val account: String?,
        val debitMinor: Long,
        val creditMinor: Long,
        val partyType: String?,
        val party: String?,
    )
    data class DraftSummary(
        val doctype: String,
        val name: String,
        val docstatus: Int,
        val company: String?,
        val paymentType: String?,
        val paidFrom: String?,
        val paidTo: String?,
        val paidAmountMinor: Long?,
        val partyType: String?,
        val party: String?,
        val totalDebitMinor: Long?,
        val accounts: List<DraftAccountRow>,
    )

    private fun optStr(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val s = obj.optString(key, "")
        return s.ifEmpty { null }
    }

    private fun optMinor(obj: JSONObject, key: String): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return try {
            ErpReports.toMinor(obj.opt(key))
        } catch (_: Exception) {
            null
        }
    }

    suspend fun listCompanies(c: ErpClient): List<CompanyInfo> {
        val arr = listRows(
            c,
            "Company",
            mapOf(
                "fields" to JSONArray().put("name").put("default_currency"),
                "limit_page_length" to 0,
            ),
        )
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CompanyInfo(o.getString("name"), o.optString("default_currency", ""))
        }
    }

    internal fun parseBankAccountRows(arr: JSONArray): List<BankAccountInfo> {
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val isComp = o.optInt("is_company_account", 0) != 0 || o.optBoolean("is_company_account", false)
            val isDisabled = o.optInt("disabled", 0) != 0 || o.optBoolean("disabled", false)
            BankAccountInfo(
                name = o.getString("name"),
                glAccount = optStr(o, "account") ?: "",
                bankAccountNo = optStr(o, "bank_account_no"),
                isCompanyAccount = isComp,
                bank = optStr(o, "bank"),
                company = optStr(o, "company"),
                partyType = optStr(o, "party_type"),
                disabled = isDisabled,
            )
        }
    }

    internal fun resolveBankAccounts(
        bankRows: List<BankAccountInfo>,
        ledgerAccounts: List<LedgerAccountInfo>,
        company: String,
    ): List<BankAccountInfo> {
        val companyGlNames = ledgerAccounts.mapTo(HashSet()) { it.name }
        val kept = bankRows.filter { row ->
            if (row.disabled || !row.partyType.isNullOrBlank()) return@filter false
            when {
                row.company == company -> true
                row.company.isNullOrBlank() -> {
                    if (row.glAccount.isNotBlank()) {
                        companyGlNames.isEmpty() || row.glAccount in companyGlNames
                    } else {
                        true
                    }
                }
                else -> false
            }
        }
        val coveredGl = kept.mapNotNullTo(HashSet()) { it.glAccount.takeIf { gl -> gl.isNotBlank() } }
        val extraGl = ledgerAccounts
            .filter { it.accountType.equals("Bank", ignoreCase = true) && it.name !in coveredGl }
            .map { gl ->
                BankAccountInfo(
                    name = gl.name,
                    glAccount = gl.name,
                    bankAccountNo = null,
                    isCompanyAccount = true,
                    bank = null,
                    company = company,
                )
            }
        return kept + extraGl
    }

    suspend fun listBankAccounts(c: ErpClient, company: String): List<BankAccountInfo> {
        var bankErr: Throwable? = null
        val bankRows = try {
            val arr = listRows(
                c,
                "Bank Account",
                mapOf(
                    "fields" to JSONArray()
                        .put("name")
                        .put("account")
                        .put("bank_account_no")
                        .put("is_company_account")
                        .put("bank")
                        .put("company")
                        .put("party_type")
                        .put("disabled"),
                    "limit_page_length" to 0,
                ),
            )
            parseBankAccountRows(arr)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErpAuthError) {
            if (e.status != 403) throw e
            bankErr = e
            emptyList()
        }

        val ledgerAccounts = try {
            listLedgerAccounts(c, company)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErpAuthError) {
            if (e.status != 403) throw e
            if (bankErr != null) throw bankErr
            emptyList()
        } catch (e: Throwable) {
            if (bankErr != null) throw bankErr
            emptyList()
        }

        val resolved = resolveBankAccounts(bankRows, ledgerAccounts, company)
        if (resolved.isEmpty() && bankErr != null) throw bankErr
        return resolved
    }

    suspend fun listLedgerAccounts(c: ErpClient, company: String, rootType: String? = null): List<LedgerAccountInfo> {
        val filters = JSONArray()
            .put(JSONArray().put("company").put("=").put(company))
            .put(JSONArray().put("is_group").put("=").put(0))
        if (rootType != null) {
            filters.put(JSONArray().put("root_type").put("=").put(rootType))
        }
        val arr = listRows(
            c,
            "Account",
            mapOf(
                "fields" to JSONArray().put("name").put("account_type"),
                "filters" to filters,
                "limit_page_length" to 0,
            ),
        )
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LedgerAccountInfo(o.getString("name"), optStr(o, "account_type"))
        }
    }

    suspend fun listModesOfPayment(c: ErpClient): List<ModeOfPaymentInfo> {
        val arr = listRows(
            c,
            "Mode of Payment",
            mapOf(
                "fields" to JSONArray().put("name").put("type"),
                "filters" to JSONArray().put(JSONArray().put("enabled").put("=").put(1)),
                "order_by" to "name asc",
                "limit_page_length" to 0,
            ),
        )
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ModeOfPaymentInfo(o.getString("name"), optStr(o, "type"))
        }
    }

    private fun nameField(dt: PartyDoctype): String = when (dt) {
        PartyDoctype.CUSTOMER -> "customer_name"
        PartyDoctype.SUPPLIER -> "supplier_name"
        PartyDoctype.EMPLOYEE -> "employee_name"
    }

    suspend fun searchParties(c: ErpClient, doctype: PartyDoctype, q: String, exact: Boolean = false): List<PartySearchHit> {
        val field = nameField(doctype)
        val params = if (exact) {
            mapOf(
                "fields" to JSONArray().put("name").put(field),
                "filters" to JSONArray().put(JSONArray().put(field).put("=").put(q)),
                "order_by" to "creation asc",
                "limit_page_length" to 20,
            )
        } else {
            val like = "%$q%"
            mapOf(
                "fields" to JSONArray().put("name").put(field),
                "or_filters" to JSONArray()
                    .put(JSONArray().put("name").put("like").put(like))
                    .put(JSONArray().put(field).put("like").put(like)),
                "limit_page_length" to 20,
            )
        }
        val arr = listRows(c, doctype.wire, params)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("name")
            val dn = optStr(o, field)?.trim()?.takeIf { it.isNotEmpty() } ?: id
            PartySearchHit(PartyRef(doctype, id), dn)
        }
    }

    suspend fun createParty(c: ErpClient, doctype: PartyDoctype, fields: JSONObject): PartyRef {
        val hasName = when (doctype) {
            PartyDoctype.EMPLOYEE -> !optStr(fields, "first_name").isNullOrBlank() || !optStr(fields, "employee_name").isNullOrBlank()
            else -> !optStr(fields, nameField(doctype)).isNullOrBlank()
        }
        if (!hasName) {
            val req = if (doctype == PartyDoctype.EMPLOYEE) "first_name" else nameField(doctype)
            throw ErpValidationError("Enter a name for the ${doctype.wire.lowercase()}.", 0, listOf(req))
        }
        val r = c.postObject(resource(doctype.wire), fields)
        val name = r?.optJSONObject("data")?.let { optStr(it, "name") }
            ?: throw ErpHttp.unexpectedResponse()
        return PartyRef(doctype, name)
    }

    suspend fun getRequiredFields(c: ErpClient, doctype: String): List<RequiredFieldInfo> {
        val r = c.getObject(
            "/api/method/frappe.desk.form.load.getdoctype",
            mapOf("doctype" to doctype, "with_parent" to 1),
        )
        val docs = r.optJSONArray("docs") ?: throw ErpHttp.unexpectedResponse()
        var main: JSONObject? = null
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i)
            if (d?.optString("name") == doctype) {
                main = d
                break
            }
        }
        val fields = main?.optJSONArray("fields") ?: throw ErpHttp.unexpectedResponse()
        val out = mutableListOf<RequiredFieldInfo>()
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: continue
            val reqd = f.optInt("reqd", 0) == 1 || f.optString("reqd") == "1"
            val hasDefault = f.has("default") && !f.isNull("default") && !f.opt("default")?.toString().isNullOrEmpty()
            if (reqd && !hasDefault) {
                val fn = f.optString("fieldname", "")
                if (fn.isNotEmpty()) {
                    out.add(
                        RequiredFieldInfo(
                            fieldname = fn,
                            label = optStr(f, "label") ?: fn,
                            fieldtype = f.optString("fieldtype", "Data"),
                        )
                    )
                }
            }
        }
        return out
    }

    suspend fun listOpenInvoices(c: ErpClient, partyType: PartyDoctype, party: String): List<OpenInvoice> {
        val doctype = if (partyType == PartyDoctype.CUSTOMER) "Sales Invoice" else "Purchase Invoice"
        val partyField = if (partyType == PartyDoctype.CUSTOMER) "customer" else "supplier"
        val arr = listRows(
            c,
            doctype,
            mapOf(
                "fields" to JSONArray().put("name").put("outstanding_amount").put("due_date"),
                "filters" to JSONArray()
                    .put(JSONArray().put("docstatus").put("=").put(1))
                    .put(JSONArray().put("outstanding_amount").put(">").put(0))
                    .put(JSONArray().put(partyField).put("=").put(party)),
                "order_by" to "due_date asc",
                "limit_page_length" to 0,
            ),
        )
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            OpenInvoice(
                doctype = doctype,
                name = o.getString("name"),
                outstandingMinor = ErpReports.toMinor(o.opt("outstanding_amount")),
                dueDate = optStr(o, "due_date") ?: "",
            )
        }
    }

    private val OPEN_ORDER_STATUSES = setOf("To Deliver and Bill", "To Bill", "To Deliver")

    suspend fun listOpenSalesOrders(c: ErpClient, customer: String): List<OpenOrder> {
        val statuses = JSONArray().apply { OPEN_ORDER_STATUSES.forEach { put(it) } }
        val arr = listRows(
            c,
            "Sales Order",
            mapOf(
                "fields" to JSONArray()
                    .put("name").put("grand_total").put("rounded_total")
                    .put("base_grand_total").put("base_rounded_total")
                    .put("advance_paid").put("delivery_date").put("status")
                    .put("docstatus").put("transaction_date"),
                "filters" to JSONArray()
                    .put(JSONArray().put("customer").put("=").put(customer))
                    .put(JSONArray().put("docstatus").put("=").put(1))
                    .put(JSONArray().put("status").put("in").put(statuses)),
                "order_by" to "transaction_date asc, creation asc",
                "limit_page_length" to 0,
            ),
        )
        val out = mutableListOf<OpenOrder>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val docstatus = r.optInt("docstatus", -1)
            val status = r.optString("status", "")
            if (docstatus != 1 || status !in OPEN_ORDER_STATUSES) continue
            val rounded = optMinor(r, "base_rounded_total")?.takeIf { it != 0L }
            val baseVal = if (r.has("base_grand_total") && !r.isNull("base_grand_total")) r.opt("base_grand_total") else r.opt("grand_total")
            val totalMinor = rounded ?: ErpReports.toMinor(baseVal)
            val advanceMinor = ErpReports.toMinor(r.opt("advance_paid"))
            val remainingMinor = totalMinor - advanceMinor
            if (remainingMinor <= 0L) continue
            out.add(
                OpenOrder(
                    name = r.getString("name"),
                    totalMinor = totalMinor,
                    advanceMinor = advanceMinor,
                    remainingMinor = remainingMinor,
                    deliveryDate = optStr(r, "delivery_date"),
                    orderDate = optStr(r, "transaction_date"),
                )
            )
        }
        return out.sortedBy { it.orderDate ?: "" }
    }

    /** Creates a DRAFT only (docstatus = 0 is forced). Never submits. */
    suspend fun createDraft(c: ErpClient, doctype: String, body: JSONObject): String {
        val payload = JSONObject(body.toString()).put("docstatus", 0)
        val r = c.postObject(resource(doctype), payload)
        val name = r?.optJSONObject("data")?.let { optStr(it, "name") }
            ?: throw ErpHttp.unexpectedResponse()
        return name
    }

    private const val UTR_HITS_PER_DOCTYPE = 20
    private val UTR_FIELDS = listOf("Payment Entry" to "reference_no", "Journal Entry" to "cheque_no")

    suspend fun findDraftsByUtr(c: ErpClient, reference: String): List<UtrHit> {
        val out = mutableListOf<UtrHit>()
        for ((doctype, field) in UTR_FIELDS) {
            val arr = listRows(
                c,
                doctype,
                mapOf(
                    "fields" to JSONArray().put("name").put("docstatus"),
                    "filters" to JSONArray()
                        .put(JSONArray().put(field).put("=").put(reference))
                        .put(JSONArray().put("docstatus").put("!=").put(2)),
                    "order_by" to "creation asc",
                    "limit_page_length" to UTR_HITS_PER_DOCTYPE,
                ),
            )
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                out.add(UtrHit(doctype, r.getString("name"), r.optInt("docstatus", 0)))
            }
        }
        return out
    }

    suspend fun getDraftSummary(c: ErpClient, doctype: String, name: String): DraftSummary {
        val r = c.getObject("${resource(doctype)}/${ErpHttp.encodeSegment(name)}")
        val d = r.optJSONObject("data") ?: throw ErpHttp.unexpectedResponse()
        if (!d.has("docstatus") || d.isNull("docstatus")) throw ErpHttp.unexpectedResponse()
        val accountsArr = d.optJSONArray("accounts")
        val accounts = if (accountsArr != null) {
            (0 until accountsArr.length()).mapNotNull { i ->
                val row = accountsArr.optJSONObject(i) ?: return@mapNotNull null
                DraftAccountRow(
                    account = optStr(row, "account"),
                    debitMinor = optMinor(row, "debit_in_account_currency") ?: optMinor(row, "debit") ?: 0L,
                    creditMinor = optMinor(row, "credit_in_account_currency") ?: optMinor(row, "credit") ?: 0L,
                    partyType = optStr(row, "party_type"),
                    party = optStr(row, "party"),
                )
            }
        } else {
            emptyList()
        }
        return DraftSummary(
            doctype = doctype,
            name = name,
            docstatus = d.getInt("docstatus"),
            company = optStr(d, "company"),
            paymentType = optStr(d, "payment_type"),
            paidFrom = optStr(d, "paid_from"),
            paidTo = optStr(d, "paid_to"),
            paidAmountMinor = optMinor(d, "paid_amount"),
            partyType = optStr(d, "party_type"),
            party = optStr(d, "party"),
            totalDebitMinor = optMinor(d, "total_debit"),
            accounts = accounts,
        )
    }

    suspend fun deleteDraft(c: ErpClient, doctype: String, name: String) {
        val path = "${resource(doctype)}/${ErpHttp.encodeSegment(name)}"
        val r = c.getObject(path)
        val data = r.optJSONObject("data") ?: throw ErpHttp.unexpectedResponse()
        if (!data.has("docstatus") || data.isNull("docstatus")) throw ErpHttp.unexpectedResponse()
        val docstatus = data.getInt("docstatus")
        if (docstatus != 0) throw NotDraftError(doctype, name, docstatus)
        c.delete(path)
    }

    const val BOOKED_PAGE_SIZE = 500
    private const val BOOKED_MAX_PAGES = 3

    data class ReadBookedResult(
        val entries: List<SimilarEngine.BookedEntry>,
        val checkedFrom: String?,
    )

    suspend fun readBookedEntries(
        c: ErpClient,
        company: String,
        fromDate: String,
        toDate: String,
    ): ReadBookedResult {
        val entries = mutableListOf<SimilarEngine.BookedEntry>()
        var checkedFrom: String? = null

        val sources = listOf(
            Triple(
                "Payment Entry",
                listOf("name", "posting_date", "payment_type", "paid_amount", "received_amount", "party", "party_name", "reference_no", "docstatus", "company"),
                { r: JSONObject ->
                    val pt = optStr(r, "payment_type")
                    val amt = optMinor(r, if (pt == "Receive") "received_amount" else "paid_amount") ?: 0L
                    val dir = when (pt) {
                        "Receive" -> Direction.IN
                        "Pay" -> Direction.OUT
                        else -> null
                    }
                    Triple(amt, dir, (optStr(r, "party_name") ?: optStr(r, "party")) to optStr(r, "reference_no"))
                },
            ),
            Triple(
                "Journal Entry",
                listOf("name", "posting_date", "total_debit", "cheque_no", "docstatus", "company"),
                { r: JSONObject ->
                    Triple(optMinor(r, "total_debit") ?: 0L, null, null to optStr(r, "cheque_no"))
                },
            ),
            Triple(
                "Purchase Invoice",
                listOf("name", "posting_date", "base_grand_total", "base_rounded_total", "supplier", "supplier_name", "bill_no", "docstatus", "company"),
                { r: JSONObject ->
                    val amt = (optMinor(r, "base_rounded_total")?.takeIf { it != 0L }) ?: optMinor(r, "base_grand_total") ?: 0L
                    Triple(amt, Direction.OUT, (optStr(r, "supplier_name") ?: optStr(r, "supplier")) to optStr(r, "bill_no"))
                },
            ),
        )

        for ((doctype, fieldsList, mapper) in sources) {
            var oldest: String? = null
            val fieldsArr = JSONArray().apply { fieldsList.forEach { put(it) } }
            val docstatusIn = JSONArray().put(0).put(1)
            val filters = JSONArray()
                .put(JSONArray().put("company").put("=").put(company))
                .put(JSONArray().put("docstatus").put("in").put(docstatusIn))
                .put(JSONArray().put("posting_date").put(">=").put(fromDate))
                .put(JSONArray().put("posting_date").put("<=").put(toDate))

            for (page in 0 until BOOKED_MAX_PAGES) {
                val arr = listRows(
                    c,
                    doctype,
                    mapOf(
                        "fields" to fieldsArr,
                        "filters" to filters,
                        "order_by" to "posting_date desc, name asc",
                        "limit_start" to page * BOOKED_PAGE_SIZE,
                        "limit_page_length" to BOOKED_PAGE_SIZE,
                    ),
                )
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    val pd = optStr(r, "posting_date")
                    if (pd != null && (oldest == null || pd < oldest)) oldest = pd
                    val ds = r.optInt("docstatus", -1)
                    if (optStr(r, "company") != company || (ds != 0 && ds != 1) || pd == null) continue
                    val (amountMinor, direction, partyRefPair) = mapper(r)
                    entries.add(
                        SimilarEngine.BookedEntry(
                            doctype = doctype,
                            name = r.getString("name"),
                            postingDate = pd,
                            amountMinor = amountMinor,
                            direction = direction,
                            party = partyRefPair.first,
                            reference = partyRefPair.second,
                            docstatus = ds,
                        )
                    )
                }
                if (arr.length() < BOOKED_PAGE_SIZE) break
                if (page == BOOKED_MAX_PAGES - 1 && oldest != null && (checkedFrom == null || oldest > checkedFrom)) {
                    checkedFrom = oldest
                }
            }
        }
        return ReadBookedResult(entries = entries, checkedFrom = checkedFrom)
    }

    private const val STATUS_CHUNK = 50

    suspend fun getDocStatuses(c: ErpClient, refs: List<Pair<String, String>>): Map<String, Int> {
        val byType = LinkedHashMap<String, MutableSet<String>>()
        for ((dt, name) in refs) {
            byType.getOrPut(dt) { LinkedHashSet() }.add(name)
        }
        val out = HashMap<String, Int>()
        for ((doctype, set) in byType) {
            val names = set.toList()
            for (chunk in names.chunked(STATUS_CHUNK)) {
                val namesArr = JSONArray().apply { chunk.forEach { put(it) } }
                val arr = listRows(
                    c,
                    doctype,
                    mapOf(
                        "fields" to JSONArray().put("name").put("docstatus"),
                        "filters" to JSONArray().put(JSONArray().put("name").put("in").put(namesArr)),
                        "limit_page_length" to 0,
                    ),
                )
                for (i in 0 until arr.length()) {
                    val row = arr.getJSONObject(i)
                    out["$doctype:${row.getString("name")}"] = row.getInt("docstatus")
                }
            }
        }
        return out
    }
}
