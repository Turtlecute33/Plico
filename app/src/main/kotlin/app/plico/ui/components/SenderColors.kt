package app.plico.ui.components

import java.util.Locale

/**
 * A colour per sender for unread rows (DESIGN §13). It reuses the [AccountColors] palette, which is checked for
 * contrast in both themes and does not change with the dynamic colour scheme. The address only picks an index;
 * it never leaves the phone.
 */
object SenderColors {
    /** The [AccountColors] index of [address]: the same for every case of the address, always in the palette. */
    fun index(address: String): Int = Math.floorMod(mix(address.lowercase(Locale.ROOT).hashCode()), AccountColors.COUNT)

    /**
     * The MurmurHash3 finalizer. The low bits of `String.hashCode` are only an alternating sum of the characters,
     * so similar addresses would share few colours; after this mix every bit depends on every character.
     */
    private fun mix(hash: Int): Int {
        var h = hash
        h = h xor (h ushr 16)
        h *= -0x7a143595 // 0x85ebca6b
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb // 0xc2b2ae35
        return h xor (h ushr 16)
    }
}
