package model

import kotlinx.serialization.Serializable
import java.util.Collections

@Serializable
data class AccountState(val balance: Long, val nextNonce: Long) {
    init {
        require(balance >= 0)
        require(nextNonce >= 0)
    }
}

class LedgerSnapshot(accounts: Map<Address, AccountState>) {
    val accounts: Map<Address, AccountState> = Collections.unmodifiableMap(LinkedHashMap(accounts))
}
