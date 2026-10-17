package model

import java.util.Collections

internal fun <T> frozenList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
