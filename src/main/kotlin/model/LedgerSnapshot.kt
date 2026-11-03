package model

import kotlinx.serialization.Serializable
import java.util.Collections

/** Подтверждённый баланс и nonce, ожидаемый для следующего перевода со счёта. */
@Serializable
data class AccountState(val balance: Long, val nextNonce: Long) {
    init {
        require(balance >= 0)
        require(nextNonce >= 0)
    }
}

/** Неизменяемое представление счетов для проверки транзакций и блоков. */
class LedgerSnapshot(accounts: Map<Address, AccountState>) {
    val accounts: Map<Address, AccountState> = Collections.unmodifiableMap(LinkedHashMap(accounts))
}
