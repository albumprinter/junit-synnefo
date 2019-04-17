package albelli.junit.synnefo.runtime

internal fun <E> MutableList<E>.dequeueUpTo(limit: Int): MutableList<E> {
    val from = Math.max(0, this.size - limit)
    val to = Math.min(this.size, from + limit)
    return this.subList(from, to)
}

internal fun StringBuilder.appendWithEscaping(s: String) {
    if (s.contains(' '))
        this.append("\"$s\" ")
    else
        this.append("$s ")
}
