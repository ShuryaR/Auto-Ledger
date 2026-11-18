package app.onebook.autoledger.erp

import app.onebook.autoledger.core.PartyDoctype
import app.onebook.autoledger.core.PartyRef
import org.json.JSONObject

interface ErpOps {
    suspend fun findDraftsByUtr(reference: String): List<ErpDocs.UtrHit>
    suspend fun getDraftSummary(doctype: String, name: String): ErpDocs.DraftSummary
    suspend fun createDraft(doctype: String, body: JSONObject): String
    suspend fun deleteDraft(doctype: String, name: String)
    suspend fun searchParties(doctype: PartyDoctype, q: String, exact: Boolean = false): List<ErpDocs.PartySearchHit>
    suspend fun getDocStatuses(refs: List<Pair<String, String>>): Map<String, Int>
    suspend fun createParty(doctype: PartyDoctype, fields: JSONObject): PartyRef
}

fun makeErpOps(client: ErpClient): ErpOps = object : ErpOps {
    override suspend fun findDraftsByUtr(reference: String) = ErpDocs.findDraftsByUtr(client, reference)

    override suspend fun getDraftSummary(doctype: String, name: String) = ErpDocs.getDraftSummary(client, doctype, name)

    override suspend fun createDraft(doctype: String, body: JSONObject): String {
        if (doctype != "Payment Entry" && doctype != "Journal Entry") {
            throw ErpValidationError("AutoLedger cannot create a $doctype.", 0)
        }
        return ErpDocs.createDraft(client, doctype, body)
    }

    override suspend fun deleteDraft(doctype: String, name: String) = ErpDocs.deleteDraft(client, doctype, name)

    override suspend fun searchParties(doctype: PartyDoctype, q: String, exact: Boolean) =
        ErpDocs.searchParties(client, doctype, q, exact)

    override suspend fun getDocStatuses(refs: List<Pair<String, String>>) = ErpDocs.getDocStatuses(client, refs)

    override suspend fun createParty(doctype: PartyDoctype, fields: JSONObject) =
        ErpDocs.createParty(client, doctype, fields)
}
