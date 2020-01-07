package albelli.junit.synnefo.runtime.exceptions

class SynnefoTestStoppedException(message: String) : RuntimeException(message) {
    @Synchronized
    override fun fillInStackTrace(): Throwable {
        return this
    }
}

