package albelli.junit.synnefo.runtime.exceptions

class SynnefoTestTimedOutException(message: String) : RuntimeException(message) {
    @Synchronized
    override fun fillInStackTrace(): Throwable {
        return this
    }
}