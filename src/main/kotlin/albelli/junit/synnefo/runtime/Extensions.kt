package albelli.junit.synnefo.runtime

import java.io.File
import java.net.URI
import java.net.URL

internal fun <E> MutableList<E>.dequeueUpTo(limit: Int): MutableList<E> {
    val from = Math.max(0, this.size - limit)
    val to = Math.min(this.size, from + limit)
    val sub = this.subList(from, to)
    val copySub = ArrayList(sub)
    sub.clear()
    return copySub
}

internal fun StringBuilder.appendWithEscaping(s: String) {
    if (s.contains(' ') || s.contains(':'))
        this.append("\"$s\" ")
    else
        this.append("$s ")
}

internal fun String?.isNullOrWhiteSpace(): Boolean {
    if(this == null)
        return true

    return this.trim { it <= ' ' }.isEmpty()
}

internal fun URI.toValidURL(classLoader: ClassLoader): URL {
    if ("classpath" == this.scheme.toLowerCase()) {
        var path = this.path
        if (path.startsWith("/")) {
            path = path.substring("/".length)
        }
        return classLoader.getResource(path)
    } else return if (this.scheme == null && this.path != null) {
        //Assume that its a file.
        File(this.path).toURI().toURL()
    } else {
        this.toURL()
    }
}
