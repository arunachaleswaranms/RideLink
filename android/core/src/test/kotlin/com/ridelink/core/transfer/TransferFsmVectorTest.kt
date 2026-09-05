package com.ridelink.core.transfer

import com.ridelink.core.testutil.Vectors
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs `protocol/vectors/transfer-fsm/transfer_fsm_vectors.json` — ADR-023's transfer state machine. */
class TransferFsmVectorTest {
    private fun eventFrom(o: JsonObject): TransferEvent =
        when (val kind = o["kind"]!!.jsonPrimitive.content) {
            "Enqueued" -> TransferEvent.Enqueued
            "Dequeued" -> TransferEvent.Dequeued
            "OfferReceived" ->
                TransferEvent.OfferReceived(o["size_bytes"]!!.jsonPrimitive.long, o["chunk_count"]!!.jsonPrimitive.long.toInt())
            "OfferRejected" -> TransferEvent.OfferRejected(TransferError.valueOf(o["error"]!!.jsonPrimitive.content))
            "BytesReceived" -> TransferEvent.BytesReceived(o["bytes"]!!.jsonPrimitive.long)
            "SizeMismatchDetected" -> TransferEvent.SizeMismatchDetected
            "AllBytesReceived" -> TransferEvent.AllBytesReceived
            "HashVerified" -> TransferEvent.HashVerified(o["matches"]!!.jsonPrimitive.boolean)
            "IoErrorDetected" -> TransferEvent.IoErrorDetected
            "DiskFullDetected" -> TransferEvent.DiskFullDetected
            "ConnectionLost" -> TransferEvent.ConnectionLost
            "SessionInvalidated" -> TransferEvent.SessionInvalidated
            "Cancelled" -> TransferEvent.Cancelled
            else -> error("unknown event kind $kind")
        }

    private fun actionLabel(a: JsonObject): String = a["kind"]!!.jsonPrimitive.content

    private fun actionLabel(a: TransferAction): String =
        when (a) {
            TransferAction.SendTransferRequest -> "SendTransferRequest"
            TransferAction.OpenBulkConnection -> "OpenBulkConnection"
            TransferAction.WriteChunkToPart -> "WriteChunkToPart"
            is TransferAction.ReportProgress -> "ReportProgress"
            TransferAction.ComputeHashFromDisk -> "ComputeHashFromDisk"
            TransferAction.PromoteCacheEntry -> "PromoteCacheEntry"
            TransferAction.DeletePartFile -> "DeletePartFile"
            TransferAction.CloseBulkConnection -> "CloseBulkConnection"
            is TransferAction.NotifyUi -> "NotifyUi"
        }

    @Test
    fun runVectors() {
        val doc = Vectors.load("transfer-fsm/transfer_fsm_vectors.json").jsonObject
        val rows = doc["rows"]!!.jsonArray
        var checked = 0
        for (element in rows) {
            val row = element.jsonObject
            val name = row["name"]!!.jsonPrimitive.content
            val status = TransferStatus.valueOf(row["status"]!!.jsonPrimitive.content)
            val event = eventFrom(row["event"]!!.jsonObject)
            val expect = row["expect"]!!.jsonObject

            val transition = TransferReducer.apply(status, event)
            assertEquals(TransferStatus.valueOf(expect["status"]!!.jsonPrimitive.content), transition.status, "vector $name: status")
            val expectedActions = expect["actions"]!!.jsonArray.map { it.jsonObject }.map(::actionLabel)
            assertEquals(expectedActions, transition.actions.map(::actionLabel), "vector $name: actions")
            val expectedError = expect["error"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(expectedError, transition.error?.name, "vector $name: error")
            checked += 1
        }
        assertEquals(47, checked, "expected 47 rows")
    }
}
