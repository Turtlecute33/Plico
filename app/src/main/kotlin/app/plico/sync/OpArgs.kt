package app.plico.sync

import app.plico.data.FolderRole
import app.plico.data.OpKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The `op.arg` JSON of each [OpKind] (DESIGN.md §7.4). Mailboxes are named by path, so that an op outlives a
 * folder-list refresh; the op's rows give the UIDs when it runs.
 */
internal sealed interface OpArg {
    val kind: String

    /** `±FLAGS` [flag] on the op's messages. */
    data class Flags(val flag: String, val on: Boolean) : OpArg {
        override val kind get() = OpKind.FLAGS
    }

    /** Gmail `±X-GM-LABELS` on the op's messages; system labels keep their backslash (`\Inbox`). */
    data class Labels(val add: List<String>, val remove: List<String>) : OpArg {
        override val kind get() = OpKind.LABELS
    }

    /** `UID MOVE` of the op's messages to [path]; [role] is given to the mailbox when it must be created. */
    data class Move(val path: String, val role: FolderRole?) : OpArg {
        override val kind get() = OpKind.MOVE
    }

    /** `\Deleted` + `UID EXPUNGE` of the op's messages, or of UIDs 1..[upTo] of [folderId] (Empty Trash/Spam). */
    data class Expunge(val folderId: Long? = null, val upTo: Long = 0) : OpArg {
        override val kind get() = OpKind.EXPUNGE
    }

    /**
     * `APPEND` to the mailbox with [role]: [FolderRole.DRAFTS] uploads the current content of draft [draftId];
     * [FolderRole.SENT] uploads the Sent copy [file] from the draft's outbox dir.
     */
    data class Append(val draftId: Long, val role: FolderRole, val file: String? = null) : OpArg {
        override val kind get() = OpKind.APPEND
    }

    /** Deletes the copies of [messageId] in the Drafts mailbox, except UID [keep] (0 = none). */
    data class DeleteDraft(val messageId: String, val keep: Long = 0) : OpArg {
        override val kind get() = OpKind.DELETE_DRAFT
    }

    companion object {
        fun encode(arg: OpArg): String = when (arg) {
            is Flags -> JSONObject().put("flag", arg.flag).put("on", arg.on)
            is Labels -> JSONObject().put("add", JSONArray(arg.add)).put("remove", JSONArray(arg.remove))
            is Move -> JSONObject().put("path", arg.path).putOpt("role", arg.role?.id)
            is Expunge -> JSONObject().putOpt("folder", arg.folderId).put("upTo", arg.upTo)
            is Append -> JSONObject().put("draft", arg.draftId).put("role", arg.role.id).putOpt("file", arg.file)
            is DeleteDraft -> JSONObject().put("messageId", arg.messageId).put("keep", arg.keep)
        }.toString()

        /** The argument of an op row; null when the kind is unknown or the JSON does not fit it. */
        fun decode(kind: String, json: String): OpArg? = try {
            val o = JSONObject(json)
            when (kind) {
                OpKind.FLAGS -> Flags(o.getString("flag"), o.getBoolean("on"))
                OpKind.LABELS -> Labels(o.getJSONArray("add").strings(), o.getJSONArray("remove").strings())
                OpKind.MOVE -> Move(o.getString("path"), FolderRole.of(o.optString("role")))
                OpKind.EXPUNGE -> Expunge(if (o.has("folder")) o.getLong("folder") else null, o.optLong("upTo"))
                OpKind.APPEND -> Append(
                    o.getLong("draft"),
                    FolderRole.of(o.getString("role")) ?: return null,
                    if (o.has("file")) o.getString("file") else null,
                )
                OpKind.DELETE_DRAFT -> DeleteDraft(o.getString("messageId"), o.optLong("keep"))
                else -> null
            }
        } catch (_: JSONException) {
            null
        }

        private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
    }
}
