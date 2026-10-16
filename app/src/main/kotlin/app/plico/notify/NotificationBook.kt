package app.plico.notify

/**
 * Which new mail the notifications show (DESIGN §9; push PB8, apis AF7). Per account: the pending
 * messages, which the summary counts, and the ones that also have a child notification. Children are
 * capped at [batchChildren] per batch and [maxChildren] over all accounts, so the app stays below the
 * system limit of 50 notifications. Pure; the Notifier uses it under its lock. Times are elapsedRealtime.
 */
internal class NotificationBook(private val batchChildren: Int = 5, private val maxChildren: Int = 39) {
    data class Item(val id: Long, val accountId: Long, val date: Long)

    /** A new-mail batch: [alert] when it added mail; post [post] after the summary; cancel [cancel]. */
    data class Batch(val alert: Boolean, val post: List<Item>, val cancel: List<Item>)

    /** The summary lines: the newest [lines] of [count] pending messages, [more] not listed. */
    data class Summary(val count: Int, val lines: List<Item>, val more: Int)

    private class Child(val item: Item, val postedAt: Long)

    private class Account {
        val pending = HashMap<Long, Item>()
        val children = HashMap<Long, Child>()
        var summaryAt = 0L
    }

    private val accounts = HashMap<Long, Account>()

    fun accounts(): Set<Long> = accounts.keys.toSet()

    fun pending(accountId: Long): List<Item> = accounts[accountId]?.pending?.values.orEmpty().sortedWith(NEWEST)

    fun children(accountId: Long): List<Item> =
        accounts[accountId]?.children?.values.orEmpty().map { it.item }.sortedWith(NEWEST)

    fun summary(accountId: Long, maxLines: Int = 5): Summary {
        val pending = pending(accountId)
        val lines = pending.take(maxLines)
        return Summary(pending.size, lines, pending.size - lines.size)
    }

    /** Adds the messages of a new-mail batch of [accountId]; [now] is the time the summary is posted. */
    fun add(accountId: Long, items: List<Item>, now: Long): Batch {
        val account = accounts.getOrPut(accountId, ::Account)
        val fresh = items.filter { it.id !in account.pending }.distinctBy { it.id }.sortedWith(NEWEST)
        fresh.forEach { account.pending[it.id] = it }
        account.summaryAt = now
        return withChildren(account, fresh.take(batchChildren), now).copy(alert = fresh.isNotEmpty())
    }

    /** Content is shown again: children for the newest pending messages of [accountId] that have none. */
    fun showChildren(accountId: Long, now: Long): Batch {
        val account = accounts[accountId] ?: return Batch(alert = false, post = emptyList(), cancel = emptyList())
        val candidates = account.pending.values.sortedWith(NEWEST).take(batchChildren)
            .filter { it.id !in account.children }
        return withChildren(account, candidates, now)
    }

    /** The summary of [accountId] was posted again at [now]. */
    fun touch(accountId: Long, now: Long) {
        accounts[accountId]?.summaryAt = now
    }

    /** Drops pending messages of [accountId] that are not in [keep]; returns the children to cancel. */
    fun retain(accountId: Long, keep: Set<Long>): List<Item> {
        val account = accounts[accountId] ?: return emptyList()
        account.pending.keys.retainAll(keep)
        return account.children.values.filter { it.item.id !in keep }.map { it.item }
            .also { gone -> gone.forEach { account.children.remove(it.id) } }
    }

    /** Forgets [accountId]; returns its children to cancel. */
    fun clear(accountId: Long): List<Item> = accounts.remove(accountId)?.children?.values?.map { it.item }.orEmpty()

    /** Content is hidden now: the account keeps only its summary. Returns the children to cancel. */
    fun dropChildren(accountId: Long): List<Item> {
        val children = accounts[accountId]?.children ?: return emptyList()
        return children.values.map { it.item }.also { children.clear() }
    }

    /**
     * Applies what the user dismissed. [summaryShown]: the summary is still in the shade; [shownChildren]: the
     * ids of children still shown. Posts younger than [graceMs] count as shown, since the system lists a
     * new notification only after a short delay. Returns the children to cancel.
     */
    fun reconcile(
        accountId: Long,
        summaryShown: Boolean,
        shownChildren: Set<Long>,
        now: Long,
        graceMs: Long,
    ): List<Item> {
        val account = accounts[accountId] ?: return emptyList()
        if (!summaryShown && now - account.summaryAt > graceMs) return clear(accountId)
        val dismissed = account.children.values.filter { it.item.id !in shownChildren && now - it.postedAt > graceMs }
        dismissed.forEach {
            account.children.remove(it.item.id)
            account.pending.remove(it.item.id)
        }
        return emptyList()
    }

    /** Rebuilds [accountId] from the notifications that survived a process restart. */
    fun restore(accountId: Long, pending: List<Item>, children: List<Item>) {
        val account = accounts.getOrPut(accountId, ::Account)
        (pending + children).forEach { account.pending[it.id] = it }
        children.forEach { account.children[it.id] = Child(it, postedAt = 0) }
    }

    /** Gives [candidates] children, keeping the newest [maxChildren] over all accounts. */
    private fun withChildren(account: Account, candidates: List<Item>, now: Long): Batch {
        val existing = accounts.values.flatMap { a -> a.children.values.map { it.item } }
        val keep = (existing + candidates).sortedWith(NEWEST).take(maxChildren).toSet()
        val cancel = existing.filterNot { it in keep }
        cancel.forEach { accounts[it.accountId]?.children?.remove(it.id) }
        val post = candidates.filter { it in keep }
        post.forEach { account.children[it.id] = Child(it, now) }
        return Batch(alert = false, post = post, cancel = cancel)
    }

    private companion object {
        val NEWEST = compareByDescending<Item> { it.date }.thenByDescending { it.id }
    }
}
