package albelli.junit.synnefo.runtime.exceptions

class SynnefoTestFailureException(message: String) : RuntimeException(message) {

    @Synchronized
    override fun fillInStackTrace(): Throwable {
        return this
    }
}