package com.r3corda.contracts.asset

import com.r3corda.contracts.clause.AbstractConserveAmount
import com.r3corda.contracts.clause.AbstractIssue
import com.r3corda.contracts.clause.NoZeroSizedOutputs
import com.r3corda.core.contracts.*
import com.r3corda.core.contracts.clauses.*
import com.r3corda.core.crypto.Party
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.crypto.newSecureRandom
import com.r3corda.core.crypto.toStringShort
import java.security.PublicKey
import java.util.*

/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
// Commodity
//

// Just a fake program identifier for now. In a real system it could be, for instance, the hash of the program bytecode.
val COMMODITY_PROGRAM_ID = CommodityContract()
    //SecureHash.sha256("commodity")

/**
 * A commodity contract represents an amount of some commodity, tracked on a distributed ledger. The design of this
 * contract is intentionally similar to the [Cash] contract, and the same commands (issue, move, exit) apply, the
 * differences are in representation of the underlying commodity. Issuer in this context means the party who has the
 * commodity, or is otherwise responsible for delivering the commodity on demand, and the deposit reference is use for
 * internal accounting by the issuer (it might be, for example, a warehouse and/or location within a warehouse).
 */
// TODO: Need to think about expiry of commodities, how to require payment of storage costs, etc.
class CommodityContract : OnLedgerAsset<Commodity, CommodityContract.State>() {
    /**
     * TODO:
     * 1) hash should be of the contents, not the URI
     * 2) allow the content to be specified at time of instance creation?
     *
     * Motivation: it's the difference between a state object referencing a programRef, which references a
     * legalContractReference and a state object which directly references both.  The latter allows the legal wording
     * to evolve without requiring code changes. But creates a risk that users create objects governed by a program
     * that is inconsistent with the legal contract
     */
    override val legalContractReference: SecureHash = SecureHash.sha256("https://www.big-book-of-banking-law.gov/commodity-claims.html")

    override val conserveClause: AbstractConserveAmount<State, Commodity> = Clauses.ConserveAmount()

    /**
     * The clauses for this contract are essentially:
     *
     * 1. Group all commodity input and output states in a transaction by issued commodity, and then for each group:
     *  a. Check there are no zero sized output states in the group, and throw an error if so.
     *  b. Check for an issuance command, and do standard issuance checks if so, THEN STOP. Otherwise:
     *  c. Check for a move command (required) and an optional exit command, and that input and output totals are correctly
     *     conserved (output = input - exit)
     */
    interface Clauses {
        /**
         * Grouping clause to extract input and output states into matched groups and then run a set of clauses over
         * each group.
         */
        class Group : GroupClauseVerifier<State, Issued<Commodity>>() {
            /**
             * The group clause does not depend on any commands being present, so something has gone terribly wrong if
             * it doesn't match.
             */
            override val ifNotMatched = MatchBehaviour.ERROR
            /**
             * The group clause is the only top level clause, so end after processing it. If there are any commands left
             * after this clause has run, the clause verifier will trigger an error.
             */
            override val ifMatched = MatchBehaviour.END
            // Subclauses to run on each group
            override val clauses = listOf(
                    NoZeroSizedOutputs<State, Commodity>(),
                    Issue(),
                    ConserveAmount()
            )

            /**
             * Group commodity states by issuance definition (issuer and underlying commodity).
             */
            override fun extractGroups(tx: TransactionForContract)
                    = tx.groupStates<State, Issued<Commodity>> { it.issuanceDef }
        }

        /**
         * Standard issue clause, specialised to match the commodity issue command.
         */
        class Issue : AbstractIssue<State, Commodity>(
                sum = { sumCommodities() },
                sumOrZero = { sumCommoditiesOrZero(it) }
        ) {
            override val requiredCommands = setOf(Commands.Issue::class.java)
        }

        /**
         * Standard clause for conserving the amount from input to output.
         */
        class ConserveAmount : AbstractConserveAmount<State, Commodity>()
    }
    
    /** A state representing a commodity claim against some party */
    data class State(
            override val amount: Amount<Issued<Commodity>>,

            /** There must be a MoveCommand signed by this key to claim the amount */
            override val owner: PublicKey
    ) : FungibleAsset<Commodity> {
        constructor(deposit: PartyAndReference, amount: Amount<Commodity>, owner: PublicKey)
            : this(Amount(amount.quantity, Issued<Commodity>(deposit, amount.token)), owner)

        override val deposit = amount.token.issuer
        override val contract = COMMODITY_PROGRAM_ID
        override val exitKeys = Collections.singleton(owner)
        override val issuanceDef = amount.token
        override val participants = listOf(owner)

        override fun move(newAmount: Amount<Issued<Commodity>>, newOwner: PublicKey): FungibleAsset<Commodity>
            = copy(amount = amount.copy(newAmount.quantity, amount.token), owner = newOwner)

        override fun toString() = "Commodity($amount at $deposit owned by ${owner.toStringShort()})"

        override fun withNewOwner(newOwner: PublicKey) = Pair(Commands.Move(), copy(owner = newOwner))
    }

    // Just for grouping
    interface Commands : FungibleAsset.Commands {
        /**
         * A command stating that money has been moved, optionally to fulfil another contract.
         *
         * @param contractHash the contract this move is for the attention of. Only that contract's verify function
         * should take the moved states into account when considering whether it is valid. Typically this will be
         * null.
         */
        data class Move(override val contractHash: SecureHash? = null) : FungibleAsset.Commands.Move, Commands

        /**
         * Allows new commodity states to be issued into existence: the nonce ("number used once") ensures the transaction
         * has a unique ID even when there are no inputs.
         */
        data class Issue(override val nonce: Long = newSecureRandom().nextLong()) : FungibleAsset.Commands.Issue, Commands

        /**
         * A command stating that money has been withdrawn from the shared ledger and is now accounted for
         * in some other way.
         */
        data class Exit(override val amount: Amount<Issued<Commodity>>) : Commands, FungibleAsset.Commands.Exit<Commodity>
    }
    override val clauses = listOf(Clauses.Group())
    override fun extractCommands(tx: TransactionForContract): List<AuthenticatedObject<FungibleAsset.Commands>>
            = tx.commands.select<CommodityContract.Commands>()

    /**
     * Puts together an issuance transaction from the given template, that starts out being owned by the given pubkey.
     */
    fun generateIssue(tx: TransactionBuilder, tokenDef: Issued<Commodity>, pennies: Long, owner: PublicKey, notary: Party)
            = generateIssue(tx, Amount(pennies, tokenDef), owner, notary)

    /**
     * Puts together an issuance transaction for the specified amount that starts out being owned by the given pubkey.
     */
    fun generateIssue(tx: TransactionBuilder, amount: Amount<Issued<Commodity>>, owner: PublicKey, notary: Party) {
        check(tx.inputStates().isEmpty())
        check(tx.outputStates().map { it.data }.sumCashOrNull() == null)
        val at = amount.token.issuer
        tx.addOutputState(TransactionState(State(amount, owner), notary))
        tx.addCommand(generateIssueCommand(), at.party.owningKey)
    }


    override fun deriveState(txState: TransactionState<State>, amount: Amount<Issued<Commodity>>, owner: PublicKey)
            = txState.copy(data = txState.data.copy(amount = amount, owner = owner))
    override fun generateExitCommand(amount: Amount<Issued<Commodity>>) = Commands.Exit(amount)
    override fun generateIssueCommand() = Commands.Issue()
    override fun generateMoveCommand() = Commands.Move()
}

/**
 * Sums the cash states in the list, throwing an exception if there are none, or if any of the cash
 * states cannot be added together (i.e. are different currencies).
 */
fun Iterable<ContractState>.sumCommodities() = filterIsInstance<CommodityContract.State>().map { it.amount }.sumOrThrow()

/** Sums the cash states in the list, returning null if there are none. */
fun Iterable<ContractState>.sumCommoditiesOrNull() = filterIsInstance<CommodityContract.State>().map { it.amount }.sumOrNull()

/** Sums the cash states in the list, returning zero of the given currency if there are none. */
fun Iterable<ContractState>.sumCommoditiesOrZero(currency: Issued<Commodity>) = filterIsInstance<CommodityContract.State>().map { it.amount }.sumOrZero<Issued<Commodity>>(currency)
