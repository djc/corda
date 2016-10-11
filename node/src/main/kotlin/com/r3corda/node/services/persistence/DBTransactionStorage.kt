package com.r3corda.node.services.persistence

import com.google.common.annotations.VisibleForTesting
import com.r3corda.core.bufferUntilSubscribed
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.node.services.TransactionStorage
import com.r3corda.core.transactions.SignedTransaction
import com.r3corda.node.utilities.*
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.statements.InsertStatement
import rx.Observable
import rx.subjects.PublishSubject
import java.util.Collections.synchronizedMap

class DBTransactionStorage : TransactionStorage {
    private object Table : JDBCHashedTable("${NODE_DATABASE_PREFIX}transactions") {
        val txId = secureHash("tx_id")
        val transaction = blob("transaction")
    }

    private class TransactionsMap : AbstractJDBCHashMap<SecureHash, SignedTransaction, Table>(Table, loadOnInit = false) {
        override fun keyFromRow(row: ResultRow): SecureHash = row[table.txId]

        override fun valueFromRow(row: ResultRow): SignedTransaction = deserializeFromBlob(row[table.transaction])

        override fun addKeyToInsert(insert: InsertStatement, entry: Map.Entry<SecureHash, SignedTransaction>, finalizables: MutableList<() -> Unit>) {
            insert[table.txId] = entry.key
        }

        override fun addValueToInsert(insert: InsertStatement, entry: Map.Entry<SecureHash, SignedTransaction>, finalizables: MutableList<() -> Unit>) {
            insert[table.transaction] = serializeToBlob(entry.value, finalizables)
        }
    }

    private val txStorage = synchronizedMap(TransactionsMap())

    override fun addTransaction(transaction: SignedTransaction) {
        synchronized(txStorage) {
            txStorage.put(transaction.id, transaction)
            updatesPublisher.onNext(transaction)
        }
    }

    override fun getTransaction(id: SecureHash): SignedTransaction? {
        synchronized(txStorage) {
            return txStorage.get(id)
        }
    }

    val updatesPublisher = PublishSubject.create<SignedTransaction>().toSerialized()
    override val updates: Observable<SignedTransaction>
        get() = updatesPublisher

    override fun track(): Pair<List<SignedTransaction>, Observable<SignedTransaction>> {
        synchronized(txStorage) {
            return Pair(txStorage.values.toList(), updates.bufferUntilSubscribed())
        }
    }

    @VisibleForTesting
    val transactions: Iterable<SignedTransaction> get() = synchronized(txStorage) {
        txStorage.values.toList()
    }
}