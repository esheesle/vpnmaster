package net.swlr.vpnmaster.vpn.wireguard

import android.net.InetAddresses
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress

/**
 * CIDR set arithmetic for WireGuard AllowedIPs manipulation.
 *
 * Android's VpnService derives routes from peer AllowedIPs; there is no
 * separate "exclude this route" primitive in the wg-android path. To exclude
 * a CIDR from the tunnel we subtract it from the peer's AllowedIPs. To force
 * a CIDR into the tunnel we append it. CIDR blocks either nest or are
 * disjoint, which keeps the subtraction straightforward.
 */
internal object CidrMath {

    data class Cidr(val address: BigInteger, val prefix: Int, val isV6: Boolean) {
        val totalBits: Int get() = if (isV6) 128 else 32
        val hostBits: Int get() = totalBits - prefix

        private fun hostMask(): BigInteger =
            if (hostBits == 0) BigInteger.ZERO
            else BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE)

        val firstAddress: BigInteger get() = address.andNot(hostMask())

        fun contains(other: Cidr): Boolean {
            if (isV6 != other.isV6) return false
            if (prefix > other.prefix) return false
            val myHostBits = totalBits - prefix
            val myMask = if (myHostBits == 0) BigInteger.ZERO
                         else BigInteger.ONE.shiftLeft(myHostBits).subtract(BigInteger.ONE)
            return firstAddress == other.firstAddress.andNot(myMask)
        }

        fun toCanonicalString(): String {
            val size = if (isV6) 16 else 4
            val bytes = toFixedBytes(firstAddress, size)
            val inet = InetAddress.getByAddress(bytes)
            return "${inet.hostAddress}/$prefix"
        }

        companion object {
            fun parse(cidr: String): Cidr {
                val s = cidr.trim()
                val slash = s.indexOf('/')
                val addrStr = if (slash >= 0) s.substring(0, slash) else s
                require(InetAddresses.isNumericAddress(addrStr)) {
                    "CIDR must use a numeric IP literal, got: $cidr"
                }
                val inet = InetAddress.getByName(addrStr)
                val isV6 = inet !is Inet4Address
                val maxPrefix = if (isV6) 128 else 32
                val prefix = if (slash >= 0) s.substring(slash + 1).toInt() else maxPrefix
                require(prefix in 0..maxPrefix) { "Invalid CIDR prefix in $cidr" }

                val raw = BigInteger(1, inet.address)
                val hostBits = maxPrefix - prefix
                val mask = if (hostBits == 0) BigInteger.ZERO
                           else BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE)
                return Cidr(raw.andNot(mask), prefix, isV6)
            }

            private fun toFixedBytes(value: BigInteger, size: Int): ByteArray {
                val raw = value.toByteArray()
                val out = ByteArray(size)
                when {
                    raw.size == size -> raw.copyInto(out)
                    raw.size < size -> raw.copyInto(out, destinationOffset = size - raw.size)
                    // BigInteger prepends a 0 byte when the high bit is set to keep the
                    // value positive. Strip it.
                    raw.size == size + 1 && raw[0].toInt() == 0 ->
                        raw.copyInto(out, startIndex = 1, endIndex = raw.size)
                    else -> throw IllegalArgumentException(
                        "Value does not fit in $size bytes: $value"
                    )
                }
                return out
            }
        }
    }

    /**
     * Subtract a single CIDR [toRemove] from [base], returning CIDR blocks that
     * cover base \ toRemove. Disjoint → [base]. toRemove ⊇ base → empty.
     * Otherwise base strictly contains toRemove, so split base in half and recurse.
     */
    fun subtract(base: Cidr, toRemove: Cidr): List<Cidr> {
        if (base.isV6 != toRemove.isV6) return listOf(base)
        if (toRemove.contains(base)) return emptyList()
        if (!base.contains(toRemove)) return listOf(base)

        val childHostBits = base.hostBits - 1
        val halfSize = BigInteger.ONE.shiftLeft(childHostBits)
        val lower = Cidr(base.firstAddress, base.prefix + 1, base.isV6)
        val upper = Cidr(base.firstAddress.add(halfSize), base.prefix + 1, base.isV6)
        return subtract(lower, toRemove) + subtract(upper, toRemove)
    }

    fun subtractAll(base: Cidr, toRemove: List<Cidr>): List<Cidr> {
        var current = listOf(base)
        for (r in toRemove) {
            if (current.isEmpty()) break
            current = current.flatMap { subtract(it, r) }
        }
        return current
    }

    fun subtractFromList(bases: List<Cidr>, toRemove: List<Cidr>): List<Cidr> =
        if (toRemove.isEmpty()) bases else bases.flatMap { subtractAll(it, toRemove) }
}
