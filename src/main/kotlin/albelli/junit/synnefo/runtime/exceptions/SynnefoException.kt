package albelli.junit.synnefo.runtime.exceptions

class SynnefoException : RuntimeException {

    constructor(message: String) : super(message)

    constructor(message: String, e: Throwable) : super(message, e)

    constructor(e: Throwable) : super(e)
}
